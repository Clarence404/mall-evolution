package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.forever;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实验 H：连接池与线程池、实例数——池子是不是越大越好，实例翻倍吞吐翻不翻倍（experiments/h-pool-sizing）。
 *
 * <p>闭环模型：固定 users 个虚拟用户，不加思考时间，收到响应立刻发下一个请求，先预热 warmupSec 秒、再正式压 durationSec 秒。
 * 闭环下吞吐由被测系统决定，「池子从 20 加到 80 吞吐变没变」可以直接读出来（开放模型给定到达速率，只能看排队与出错）。
 * 预热期间的请求名带「(预热)」，和正式的分开统计：服务刚起来时头几秒的请求很慢，足以把下单的熔断器打开。
 * 每一圈按权重二选一：下单（orderWeight，默认 5）或查订单列表（其余，默认 95）。对照组与实验组的差别全在服务的启动参数与实例数里。
 *
 * <p>虚拟用户与实验用户不是一对一的：网关限制每人每秒 1 单，订单服务限制同一 SKU 待支付最多 2 单（demo 下 1 分钟后才关单），
 * 一个实验用户一分钟最多下 6 单（3 个 SKU 各 2 单），200 个虚拟用户各绑一个实验用户的话全场最多每秒 20 单，压不出池子的差别。
 * 所以每一圈换一个新的实验用户（从 firstUserId 起依次往后取，不重复），下单在 3 个 SKU 里随机挑；
 * 查列表则在最近下单成功的用户里随机挑一个（{@link RecentBuyers}），查到的是真有订单的列表。
 *
 * <pre>
 * 参数（-D）                     默认                  说明
 * baseUrl                        http://localhost:9000 网关
 * ha.jwtSecret                   必填
 * ha.skus                        20001,20002,20003     下单的 SKU（脚本事先补足库存）
 * ha.firstUserId                 按当前时间取一段      实验用户从这个 ID 起依次往后取
 * ha.users                       200                   虚拟用户数（并发数）
 * ha.durationSec                 300                   正式压测时长（不含预热）
 * ha.warmupSec                   30                    预热多久；0 表示不预热
 * ha.orderWeight                 5                     下单占的百分比，其余是查订单列表
 * ha.buyerRing / ha.buyerMin     5000 / 200            记住最近多少个下过单的用户；攒够多少个才开始用他们查列表
 * ha.failPauseMs                 100                   请求失败后停多久再发下一个：免得快速失败（503、连接被拒）时空转把压测机打满
 * ha.timeoutMs                   5000                  客户端超时，超过记为状态 0
 * </pre>
 */
public class PoolSizingSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(PoolSizingSimulation.class);

    /** 预热期间的请求名后缀：和正式的分开统计。 */
    static final String WARMUP_SUFFIX = " (预热)";

    private final HaConfig cfg = new HaConfig("h-pool-sizing");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("baseUrl", "http://localhost:9000");
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final List<String> skus = cfg.list("ha.skus", "20001,20002,20003");
    private final long firstUserId =
            cfg.longValue("ha.firstUserId", ExperimentTokens.firstUserIdByTime(System.currentTimeMillis()));
    private final int users = cfg.integer("ha.users", 200);
    private final int durationSec = cfg.integer("ha.durationSec", 300);
    private final double orderWeight = cfg.dbl("ha.orderWeight", 5);
    private final RecentBuyers buyers =
            new RecentBuyers(cfg.integer("ha.buyerRing", 5000), cfg.integer("ha.buyerMin", 200));
    private final Duration failPause = Duration.ofMillis(cfg.integer("ha.failPauseMs", 100));
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 5000));
    private final int warmupSec = cfg.integer("ha.warmupSec", 30);

    /** 预热到什么时刻为止（epoch 毫秒）：{@link #before} 里按压测真正开始的时刻算。 */
    private volatile long measureFromMillis = Long.MAX_VALUE;

    /** 一圈：换一个实验用户 → 按权重下单或查列表（预热期间请求名带后缀）→ 失败了稍停一下。成功的请求之间没有任何停顿。 */
    private final ScenarioBuilder loop = scenario("下单与查订单（闭环）")
            .exec(forever()
                    .on(exec(s -> s.set("ok", true))
                            .feed(tokens.sequentialUsers(firstUserId))
                            .doIfOrElse(s -> System.currentTimeMillis() < measureFromMillis)
                            .then(mix(WARMUP_SUFFIX))
                            .orElse(mix(""))
                            .doIf(s -> !s.getBoolean("ok"))
                            .then(pause(failPause))));

    /** 只负责在 events.csv 记两笔：预热开始、预热结束（之后的数据才算数）。 */
    private final ScenarioBuilder control = scenario("实验控制")
            .exec(mark("预热：" + users + " 个虚拟用户，持续 " + warmupSec + " 秒"))
            .exec(pause(Duration.ofSeconds(warmupSec)))
            .exec(mark("预热结束，开始计数：" + users + " 个虚拟用户，持续 " + durationSec + " 秒"));

    {
        if (orderWeight < 0 || orderWeight > 100) {
            throw new IllegalArgumentException("ha.orderWeight 是百分比，应在 0–100 之间，实际：" + orderWeight);
        }
        log.info("实验 H 参数：{}", cfg.used());
        Duration total = Duration.ofSeconds((long) warmupSec + durationSec);
        setUp(loop.injectClosed(constantConcurrentUsers(users).during(total)), control.injectOpen(atOnceUsers(1)))
                .protocols(HaChains.protocol(baseUrl))
                .maxDuration(total);
    }

    private ChainBuilder placeOrder(String suffix) {
        return exec(s -> HaChains.newIdempotencyKey(HaChains.pickSku(s, skus)))
                .exec(HaChains.call(
                        "POST /api/orders" + suffix,
                        n -> http(n).post("/api/orders")
                                .header("Authorization", "Bearer #{token}")
                                .header("Idempotency-Key", "#{idemKey}")
                                .body(StringBody(HaChains::placeOrderBody)),
                        List.of(200, 201), // 库存备足、用户不重复，不该出现 409；出现了要看得见
                        timeout,
                        timeline))
                .exec(s -> {
                    boolean ok = succeeded(s);
                    if (ok) {
                        buyers.add(s.getString("token"));
                    }
                    return s.set("ok", ok);
                });
    }

    private ChainBuilder listOrders(String suffix) {
        return exec(this::pickListUser)
                .exec(HaChains.call(
                        "GET /api/orders" + suffix,
                        n -> http(n).get("/api/orders?size=10").header("Authorization", "Bearer #{listToken}"),
                        List.of(200),
                        timeout,
                        timeline))
                .exec(s -> s.set("ok", succeeded(s)));
    }

    /** 权重为 0 或 100 时只剩一种请求：randomSwitch 不接受 0% 的分支。 */
    private ChainBuilder mix(String suffix) {
        if (orderWeight <= 0) {
            return listOrders(suffix);
        }
        if (orderWeight >= 100) {
            return placeOrder(suffix);
        }
        return randomSwitch()
                .on(
                        percent(orderWeight).then(placeOrder(suffix)),
                        percent(100 - orderWeight).then(listOrders(suffix)));
    }

    private ChainBuilder mark(String event) {
        return exec(s -> {
            timeline.mark(event);
            return s;
        });
    }

    /** 查列表用谁的身份：最近下单成功的用户里随机挑一个；还没攒够就用这一圈的新用户（查到空列表）。 */
    private Session pickListUser(Session s) {
        String token = buyers.pick(ThreadLocalRandom.current()::nextInt);
        return s.set("listToken", token != null ? token : s.getString("token"));
    }

    private static boolean succeeded(Session s) {
        int status = s.getInt("ha_lastStatus");
        return status >= 200 && status < 300;
    }

    @Override
    public void before() {
        measureFromMillis = System.currentTimeMillis() + warmupSec * 1000L;
        cfg.markStarted();
    }

    @Override
    public void after() {
        log.info("实验 H：压测结束时记着 {} 个最近下过单的用户", buyers.size());
        try {
            timeline.write(cfg.outDir(), cfg.usedAsCsvLines());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
