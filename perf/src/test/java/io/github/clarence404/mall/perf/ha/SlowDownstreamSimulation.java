package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实验 A：下游变慢——超时、舱壁、熔断各挡住什么（experiments/a-slow-downstream）。
 *
 * <p>两路流量同时打网关：下单（经库存服务 reserve）和商品详情（不依赖 reserve，和下单共用 mall-app 的 Tomcat 线程池）。
 * 第 faultAtSec 秒给库存服务的 reserve 注入 faultDelayMs 的延迟，持续 faultSec 秒后撤销。对照组与实验组的差别全在服务的启动参数里
 * （见 experiments/a-slow-downstream/*.properties），本场景不变。
 *
 * <pre>
 * 参数（-D）                 默认                     说明
 * baseUrl                    http://localhost:9000    网关
 * ha.inventoryUrl            http://localhost:8082    库存服务业务端口（注入故障用，不经网关）
 * ha.jwtSecret               必填                     dev 环境的本地占位 JWT 密钥，脚本从 application-dev.yml 读出
 * ha.users / ha.firstUserId  20000 / 900000001        实验用户
 * ha.skus                    20001,20002,20003        下单的 SKU（脚本事先补足库存）
 * ha.orderRps / ha.detailRps 40 / 100                 每秒下单数 / 每秒详情请求数（开放模型：后端变慢时压力不减）
 * ha.durationSec             360                      压测时长
 * ha.faultAtSec / ha.faultSec / ha.faultDelayMs  60 / 180 / 5000  何时注入、持续多久、reserve 延迟多少
 * ha.timeoutMs               10000                    客户端超时（App 的耐心），超过记为状态 0
 * </pre>
 */
public class SlowDownstreamSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(SlowDownstreamSimulation.class);

    private final HaConfig cfg = new HaConfig("a-slow-downstream");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("baseUrl", "http://localhost:9000");
    private final String inventoryUrl = cfg.str("ha.inventoryUrl", "http://localhost:8082");
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final int users = cfg.integer("ha.users", 20_000);
    private final long firstUserId = cfg.longValue("ha.firstUserId", 900_000_001L);
    private final List<String> skus = cfg.list("ha.skus", "20001,20002,20003");
    private final double orderRps = cfg.dbl("ha.orderRps", 40);
    private final double detailRps = cfg.dbl("ha.detailRps", 100);
    private final int durationSec = cfg.integer("ha.durationSec", 360);
    private final int faultAtSec = cfg.integer("ha.faultAtSec", 60);
    private final int faultSec = cfg.integer("ha.faultSec", 180);
    private final int faultDelayMs = cfg.integer("ha.faultDelayMs", 5000);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 10_000));

    private final ScenarioBuilder order = scenario("下单")
            .feed(tokens.circularUsers(firstUserId, users))
            .exec(s -> HaChains.newIdempotencyKey(HaChains.pickSku(s, skus)))
            .exec(HaChains.call(
                    "POST /api/orders",
                    n -> http(n).post("/api/orders")
                            .header("Authorization", "Bearer #{token}")
                            .header("Idempotency-Key", "#{idemKey}")
                            .body(StringBody(HaChains::placeOrderBody)),
                    List.of(200, 201, 409), // 409：库存不足或待支付单数到上限，是业务结果
                    timeout,
                    timeline));

    private final ScenarioBuilder detail = scenario("商品详情")
            .exec(s -> HaChains.pickSku(s, skus))
            .exec(HaChains.call(
                    "GET /api/products/{skuId}",
                    n -> http(n).get("/api/products/#{skuId}"),
                    List.of(200),
                    timeout,
                    timeline));

    /** 故障时间线：等 faultAtSec 秒 → 注入 → 等 faultSec 秒 → 撤销。规则自带有效期，脚本中断也会自己消失。 */
    private final ScenarioBuilder chaos = scenario("故障注入")
            .pause(Duration.ofSeconds(faultAtSec))
            .exec(HaChains.putFault(
                    inventoryUrl,
                    "a-reserve-slow",
                    "{\"method\":\"POST\",\"path\":\"/internal/inventory/reserve\",\"delayMs\":" + faultDelayMs
                            + ",\"ttlSeconds\":" + (faultSec + 60) + "}",
                    "注入：库存 reserve 延迟 " + faultDelayMs + "ms",
                    timeline))
            .exec(pause(Duration.ofSeconds(faultSec)))
            .exec(HaChains.deleteFault(inventoryUrl, "a-reserve-slow", "撤销：库存 reserve 恢复", timeline));

    {
        log.info("实验 A 参数：{}", cfg.used());
        setUp(
                        order.injectOpen(constantUsersPerSec(orderRps).during(Duration.ofSeconds(durationSec))),
                        detail.injectOpen(constantUsersPerSec(detailRps).during(Duration.ofSeconds(durationSec))),
                        chaos.injectOpen(atOnceUsers(1)))
                .protocols(HaChains.protocol(baseUrl))
                .maxDuration(Duration.ofSeconds(durationSec + 120));
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
