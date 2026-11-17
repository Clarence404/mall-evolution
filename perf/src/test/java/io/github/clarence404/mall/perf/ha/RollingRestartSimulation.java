package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
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
 * 实验 C：滚动发布丢不丢请求（experiments/c-rolling-restart）。
 *
 * <p>本场景只负责持续、稳定地打流量（商品详情、下单、查订单三路），不做任何注入；逐个重启 mall-app 实例由脚本完成，
 * 脚本把「开始注销 / 发 SIGTERM / 进程退出 / 新实例就绪」等时刻写进同一个结果目录的 restart-events.csv。
 * timeline.csv 按 epoch 秒记录每秒每种请求的状态码分布，两份文件按时间对齐，就能看出每一次重启丢了几个请求、丢在哪一步。
 * 客户端不重试：要看的是「发布本身」丢了什么，不让重试把它掩盖掉。
 *
 * <pre>
 * 参数（-D）                               默认               说明
 * baseUrl                                  :9000              网关
 * ha.jwtSecret                             必填
 * ha.detailRps / ha.orderRps / ha.listRps  60 / 10 / 10       三路流量
 * ha.durationSec                           600                压测时长：覆盖三个实例各重启一次再多留两分钟
 * ha.timeoutMs                             5000               客户端超时
 * </pre>
 */
public class RollingRestartSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(RollingRestartSimulation.class);

    private final HaConfig cfg = new HaConfig("c-rolling-restart");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("baseUrl", "http://localhost:9000");
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final int users = cfg.integer("ha.users", 20_000);
    private final long firstUserId = cfg.longValue("ha.firstUserId", 900_000_001L);
    private final List<String> skus = cfg.list("ha.skus", "20001,20002,20003");
    private final double detailRps = cfg.dbl("ha.detailRps", 60);
    private final double orderRps = cfg.dbl("ha.orderRps", 10);
    private final double listRps = cfg.dbl("ha.listRps", 10);
    private final int durationSec = cfg.integer("ha.durationSec", 600);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 5000));

    private final ScenarioBuilder detail = scenario("商品详情")
            .exec(s -> HaChains.pickSku(s, skus))
            .exec(HaChains.call(
                    "GET /api/products/{skuId}",
                    n -> http(n).get("/api/products/#{skuId}"),
                    List.of(200),
                    timeout,
                    timeline));

    private final ScenarioBuilder order = scenario("下单")
            .feed(tokens.circularUsers(firstUserId, users))
            .exec(s -> HaChains.newIdempotencyKey(HaChains.pickSku(s, skus)))
            .exec(HaChains.call(
                    "POST /api/orders",
                    n -> http(n).post("/api/orders")
                            .header("Authorization", "Bearer #{token}")
                            .header("Idempotency-Key", "#{idemKey}")
                            .body(StringBody(HaChains::placeOrderBody)),
                    List.of(200, 201, 409),
                    timeout,
                    timeline));

    private final ScenarioBuilder list = scenario("查订单")
            .feed(tokens.circularUsers(firstUserId, users))
            .exec(HaChains.call(
                    "GET /api/orders",
                    n -> http(n).get("/api/orders?size=10").header("Authorization", "Bearer #{token}"),
                    List.of(200),
                    timeout,
                    timeline));

    {
        log.info("实验 C 参数：{}", cfg.used());
        Duration d = Duration.ofSeconds(durationSec);
        setUp(
                        detail.injectOpen(constantUsersPerSec(detailRps).during(d)),
                        order.injectOpen(constantUsersPerSec(orderRps).during(d)),
                        list.injectOpen(constantUsersPerSec(listRps).during(d)))
                .protocols(HaChains.protocol(baseUrl))
                .maxDuration(d.plusSeconds(60));
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
