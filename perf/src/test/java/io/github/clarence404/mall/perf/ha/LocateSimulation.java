package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 高性能实验共用的「固定速率混合流量」：下单、查订单列表、查订单详情各按给定的每秒次数匀速到达（开放模型），
 * 先预热 warmupSec 秒（请求名带「(预热)」，不计数），再正式压 durationSec 秒。
 *
 * <p>定位实验（experiments/i-locate）用它在一个远没压满的固定负载下录 JFR、看一次请求的时间花在哪；JVM 实验
 * （experiments/k-jvm）把下单关掉（orderRps=0）、只留两种查询，对比收集器与堆大小。速率固定时各轮的负载完全一样，
 * 耗时的差别只能来自被测的那一项改动；整场的平均值与 P99.9 看结果目录的 latency.csv（微秒）。
 *
 * <p>两种查询查的是事先灌好的种子订单（mall-app 的 /internal/demo/perf/seed，各实验的 run.ps1 会先确认灌过）：
 * 从 seedFirstUserId 起的 seedUsers 个用户里随机挑一个，列表查他最近的 20 单，详情查他最近 seedRecent 单里的一单
 * （订单号是「8 + 6 位用户序号 + 6 位第几单」，不用查库就能拼出来）。只用一小部分种子用户、只查最近的订单，是为了让这些
 * 数据页留在 MySQL 的缓冲池里：这组实验看的是应用和 JVM，不想让磁盘读混进来。下单用的是另一批用户，每人只下一单。
 *
 * <pre>
 * 参数（-D）                     默认                  说明
 * baseUrl                        http://localhost:9000 网关
 * ha.baseUrl                     同 baseUrl            要绕开网关直接压 mall-app 时填它的地址（实验脚本固定传 baseUrl=网关，用这个盖过去）
 * ha.jwtSecret                   必填
 * ha.skus                        20001,20002,20003     下单的 SKU（脚本事先补足库存）
 * ha.firstUserId                 按当前时间取一段      实验用户从这个 ID 起依次往后取，每个用户只下一单
 * ha.orderRps                    100                   正式阶段每秒下单数；0 表示不下单
 * ha.listRps                     300                   每秒查订单列表次数（GET /api/orders?size=20）
 * ha.detailRps                   200                   每秒查订单详情次数（GET /api/orders/{orderNo}）
 * ha.seedFirstUserId             800000000             种子用户的起始 ID（和 DemoPerfService 一致）
 * ha.seedUsers                   100                   查询在前多少个种子用户里挑
 * ha.seedOrdersPerUser           200                   每个种子用户有多少单
 * ha.seedRecent                  20                    详情只查每人最近多少单
 * ha.rampSec                     0                     正式阶段开头用多少秒把速率从 10% 线性升到 100%；0 表示一开始就是全速
 * ha.warmupSec / ha.durationSec  60 / 240              预热与正式压测的时长；warmupSec=0 表示不预热（冷启动实验）
 * ha.timeoutMs                   5000                  客户端超时，超过记为状态 0
 * </pre>
 */
public class LocateSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(LocateSimulation.class);

    static final String WARMUP_SUFFIX = " (预热)";

    private final HaConfig cfg = new HaConfig("locate");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("ha.baseUrl", cfg.str("baseUrl", "http://localhost:9000"));
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final List<String> skus = cfg.list("ha.skus", "20001,20002,20003");
    private final long firstUserId =
            cfg.longValue("ha.firstUserId", ExperimentTokens.firstUserIdByTime(System.currentTimeMillis()));
    private final double orderRps = cfg.dbl("ha.orderRps", 100);
    private final double listRps = cfg.dbl("ha.listRps", 300);
    private final double detailRps = cfg.dbl("ha.detailRps", 200);
    private final long seedFirstUserId = cfg.longValue("ha.seedFirstUserId", 800_000_000L);
    private final int seedUsers = cfg.integer("ha.seedUsers", 100);
    private final int seedOrdersPerUser = cfg.integer("ha.seedOrdersPerUser", 200);
    private final int seedRecent = cfg.integer("ha.seedRecent", 20);
    private final int rampSec = cfg.integer("ha.rampSec", 0);
    private final int warmupSec = cfg.integer("ha.warmupSec", 60);
    private final int durationSec = cfg.integer("ha.durationSec", 240);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 5000));

    /** 预热到什么时刻为止（epoch 毫秒）：{@link #before} 里按压测真正开始的时刻算。 */
    private volatile long measureFromMillis = Long.MAX_VALUE;

    /** 种子用户的令牌，预先签好。 */
    private final String[] seedTokens = new String[seedUsers];

    {
        for (int i = 0; i < seedUsers; i++) {
            seedTokens[i] = tokens.mint(seedFirstUserId + i);
        }
    }

    private final ScenarioBuilder order = scenario("下单")
            .feed(tokens.sequentialUsers(firstUserId))
            .doIfOrElse(s -> System.currentTimeMillis() < measureFromMillis)
            .then(placeOrder(WARMUP_SUFFIX))
            .orElse(placeOrder(""));

    private final ScenarioBuilder list = scenario("查订单列表")
            .exec(this::pickSeedOrder)
            .doIfOrElse(s -> System.currentTimeMillis() < measureFromMillis)
            .then(listOrders(WARMUP_SUFFIX))
            .orElse(listOrders(""));

    private final ScenarioBuilder detail = scenario("查订单详情")
            .exec(this::pickSeedOrder)
            .doIfOrElse(s -> System.currentTimeMillis() < measureFromMillis)
            .then(orderDetail(WARMUP_SUFFIX))
            .orElse(orderDetail(""));

    private final ScenarioBuilder control = scenario("实验控制")
            .exec(mark("预热：持续 " + warmupSec + " 秒"))
            .exec(pause(Duration.ofSeconds(warmupSec)))
            .exec(mark("预热结束，开始计数：下单 " + rate(orderRps) + "/s、订单列表 " + rate(listRps) + "/s、订单详情 " + rate(detailRps)
                    + "/s，持续 " + durationSec + " 秒"));

    {
        log.info("固定速率混合流量的参数：{}", cfg.used());
        List<PopulationBuilder> populations = new ArrayList<>();
        addPopulation(populations, order, orderRps, orderRps);
        addPopulation(populations, list, listRps, listRps);
        addPopulation(populations, detail, detailRps, detailRps);
        populations.add(control.injectOpen(atOnceUsers(1)));
        setUp(populations)
                .protocols(HaChains.protocol(baseUrl))
                .maxDuration(Duration.ofSeconds((long) warmupSec + durationSec + 30));
    }

    /** 一种请求的注入：预热阶段 warmRps、正式阶段 rps（开头可以带一段从 10% 升到 100% 的爬坡）；两段都是 0 就不加这个场景。 */
    private void addPopulation(List<PopulationBuilder> populations, ScenarioBuilder scn, double warmRps, double rps) {
        List<io.gatling.javaapi.core.OpenInjectionStep> steps = new ArrayList<>();
        if (warmupSec > 0) {
            steps.add(
                    warmRps > 0
                            ? constantUsersPerSec(warmRps).during(Duration.ofSeconds(warmupSec))
                            : io.gatling.javaapi.core.CoreDsl.nothingFor(Duration.ofSeconds(warmupSec)));
        }
        if (rps > 0) {
            int ramp = Math.min(rampSec, durationSec);
            if (ramp > 0) {
                steps.add(io.gatling.javaapi.core.CoreDsl.rampUsersPerSec(rps / 10)
                        .to(rps)
                        .during(Duration.ofSeconds(ramp)));
            }
            if (durationSec > ramp) {
                steps.add(constantUsersPerSec(rps).during(Duration.ofSeconds(durationSec - ramp)));
            }
        }
        if ((warmupSec > 0 && warmRps > 0) || rps > 0) {
            populations.add(scn.injectOpen(steps));
        }
    }

    private ChainBuilder placeOrder(String suffix) {
        return exec(s -> HaChains.newIdempotencyKey(HaChains.pickSku(s, skus)))
                .exec(HaChains.call(
                        "POST /api/orders" + suffix,
                        n -> http(n).post("/api/orders")
                                .header("Authorization", "Bearer #{token}")
                                .header("Idempotency-Key", "#{idemKey}")
                                .body(StringBody(HaChains::placeOrderBody)),
                        List.of(200, 201),
                        timeout,
                        timeline));
    }

    /** 随机挑一个种子用户和他最近的一单，写进 session：seedToken、seedOrderNo。 */
    private Session pickSeedOrder(Session s) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int user = r.nextInt(seedUsers);
        int seq = seedOrdersPerUser - 1 - r.nextInt(Math.min(seedRecent, seedOrdersPerUser));
        return s.set("seedToken", seedTokens[user]).set("seedOrderNo", String.format("8%06d%06d", user, seq));
    }

    private ChainBuilder listOrders(String suffix) {
        return exec(HaChains.call(
                "GET /api/orders" + suffix,
                n -> http(n).get("/api/orders?size=20").header("Authorization", "Bearer #{seedToken}"),
                List.of(200),
                timeout,
                timeline));
    }

    private ChainBuilder orderDetail(String suffix) {
        return exec(HaChains.call(
                "GET /api/orders/{orderNo}" + suffix,
                n -> http(n).get("/api/orders/#{seedOrderNo}").header("Authorization", "Bearer #{seedToken}"),
                List.of(200),
                timeout,
                timeline));
    }

    private ChainBuilder mark(String event) {
        return exec(s -> {
            timeline.mark(event);
            return s;
        });
    }

    private static String rate(double rps) {
        return rps == Math.rint(rps) ? String.valueOf((long) rps) : String.valueOf(rps);
    }

    @Override
    public void before() {
        measureFromMillis = System.currentTimeMillis() + warmupSec * 1000L;
        cfg.markStarted();
    }

    @Override
    public void after() {
        try {
            timeline.write(cfg.outDir(), cfg.usedAsCsvLines());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
