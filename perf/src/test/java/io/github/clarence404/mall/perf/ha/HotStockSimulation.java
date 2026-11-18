package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实验 E：热点库存——所有人抢同一个 SKU 时，单行扣减与分桶各能撑到每秒多少单（experiments/e-hot-stock）。
 *
 * <p>只有一路流量：经网关下单，全部打同一个热点 SKU。开放模型——后端变慢时到达速率不变，拐点才看得出来。
 * 每个请求换一个新的实验用户（网关每人每秒 1 单、订单服务同一 SKU 待支付最多 2 单，用户不能重复）和新的 Idempotency-Key。
 * 分不分桶、分几个桶由脚本在压测开始前调库存服务的拆桶接口决定，本场景不变。
 *
 * <ul>
 *   <li>{@code ha.mode=stair}（默认）：下单速率从 startRps 起，每 stepSec 秒加 stepRps，加到 maxRps；每一级开始时在 events.csv 记一笔
 *   <li>{@code ha.mode=steady}：固定 orderRps，持续 durationSec 秒（找到拐点后在拐点两侧各压一段，看能不能长时间稳住）
 * </ul>
 *
 * <p>两种模式前面都有一段预热（warmupSec 秒、每秒 warmupRps 单）：服务刚起来时头几秒的请求要几百毫秒到几秒（类加载、JIT、建连接），
 * 足以把下单的熔断器打开，不预热的话第一级的数据是废的。预热的请求名带「(预热)」，和正式的分开统计；events.csv 里各级的时刻已经含预热。
 *
 * <pre>
 * 参数（-D）                                          默认                  说明
 * baseUrl                                             http://localhost:9000 网关
 * ha.jwtSecret                                        必填
 * ha.skus                                             20001                 热点 SKU（只取第一个；脚本传入并事先补足库存、拆好桶）
 * ha.firstUserId                                      按当前时间取一段      实验用户从这个 ID 起依次往后取，一轮之内不重复
 * ha.mode                                             stair                 stair 或 steady
 * ha.startRps / ha.stepRps / ha.stepSec / ha.maxRps   50 / 50 / 90 / 450    stair 模式的阶梯
 * ha.orderRps / ha.durationSec                        200 / 300             steady 模式的速率与时长
 * ha.warmupSec / ha.warmupRps                         30 / 20               预热多久、每秒几单；warmupSec 为 0 表示不预热
 * ha.timeoutMs                                        3000                  客户端超时（与网关的 3 秒一致），超过记为状态 0
 * </pre>
 */
public class HotStockSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(HotStockSimulation.class);

    private final HaConfig cfg = new HaConfig("e-hot-stock");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("baseUrl", "http://localhost:9000");
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final String hotSku = cfg.list("ha.skus", "20001").getFirst();
    private final long firstUserId =
            cfg.longValue("ha.firstUserId", ExperimentTokens.firstUserIdByTime(System.currentTimeMillis()));
    private final String mode = cfg.str("ha.mode", "stair");
    private final double startRps = cfg.dbl("ha.startRps", 50);
    private final double stepRps = cfg.dbl("ha.stepRps", 50);
    private final int stepSec = cfg.integer("ha.stepSec", 90);
    private final double maxRps = cfg.dbl("ha.maxRps", 450);
    private final double orderRps = cfg.dbl("ha.orderRps", 200);
    private final int durationSec = cfg.integer("ha.durationSec", 300);
    private final int warmupSec = cfg.integer("ha.warmupSec", 30);
    private final double warmupRps = cfg.dbl("ha.warmupRps", 20);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 3000));

    /** steady 模式就是只有一级的阶梯：两种模式共用同一套注入与事件。 */
    private final StairSchedule schedule = switch (mode) {
        case "stair" -> StairSchedule.of(startRps, stepRps, stepSec, maxRps);
        case "steady" -> StairSchedule.of(orderRps, orderRps, durationSec, orderRps);
        default -> throw new IllegalArgumentException("ha.mode 只能是 stair 或 steady，实际：" + mode);
    };

    /** 预热与正式压测共用一个取号器：两段用到的实验用户接着往后取，互不重复。 */
    private final Iterator<Map<String, Object>> users = tokens.sequentialUsers(firstUserId);

    private final ScenarioBuilder warmup = placeOrder("预热", "POST /api/orders (预热)");
    private final ScenarioBuilder order = placeOrder("抢热点 SKU", "POST /api/orders");

    {
        log.info("实验 E 参数：{}", cfg.used());
        log.info(
                "实验 E 日程：预热 {} 秒，之后 {} 级，共 {} 秒、约 {} 单，最高 {}/s",
                warmupSec,
                schedule.levels().size(),
                schedule.totalSec(),
                schedule.totalRequests(),
                schedule.peakRps());
        List<OpenInjectionStep> steps = new ArrayList<>();
        if (warmupSec > 0) {
            steps.add(nothingFor(Duration.ofSeconds(warmupSec)));
        }
        schedule.levels()
                .forEach(l -> steps.add(constantUsersPerSec(l.rps()).during(Duration.ofSeconds(l.durationSec()))));
        List<PopulationBuilder> populations = new ArrayList<>();
        populations.add(order.injectOpen(steps));
        populations.add(control().injectOpen(atOnceUsers(1)));
        if (warmupSec > 0 && warmupRps > 0) {
            populations.add(warmup.injectOpen(constantUsersPerSec(warmupRps).during(Duration.ofSeconds(warmupSec))));
        }
        setUp(populations)
                .protocols(HaChains.protocol(baseUrl))
                .maxDuration(Duration.ofSeconds(warmupSec + schedule.totalSec() + 30L));
    }

    private ScenarioBuilder placeOrder(String scenarioName, String requestName) {
        return scenario(scenarioName)
                .feed(users)
                .exec(s -> HaChains.newIdempotencyKey(s.set("skuId", hotSku)))
                .exec(HaChains.call(
                        requestName,
                        n -> http(n).post("/api/orders")
                                .header("Authorization", "Bearer #{token}")
                                .header("Idempotency-Key", "#{idemKey}")
                                .body(StringBody(HaChains::placeOrderBody)),
                        // 只有下单成功才算 OK：本实验库存事先备足、用户不重复，出现 409（库存不足、待支付单数到上限）说明前提没满足，要看得见
                        List.of(200, 201),
                        timeout,
                        timeline));
    }

    /** 预热与每一级开始时在 events.csv 记一笔（第几级、新的速率），和 timeline.csv 同一时间轴，事后按它把逐秒数据切成各级。 */
    private ScenarioBuilder control() {
        ChainBuilder c = exec(s -> s);
        if (warmupSec > 0) {
            c = c.exec(mark("预热：下单 " + rate(warmupRps) + "/s，持续 " + warmupSec + " 秒"))
                    .exec(pause(Duration.ofSeconds(warmupSec)));
        }
        for (StairSchedule.Level l : schedule.levels()) {
            c = c.exec(mark("第 " + l.index() + " 级：下单 " + rate(l.rps()) + "/s，持续 " + l.durationSec() + " 秒"))
                    .exec(pause(Duration.ofSeconds(l.durationSec())));
        }
        return scenario("实验控制").exec(c).exec(mark("压测结束"));
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
