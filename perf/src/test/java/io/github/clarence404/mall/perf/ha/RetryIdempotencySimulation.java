package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实验 B2：超时重试必须配幂等（experiments/b-retry-storm 的第二部分）。
 *
 * <p>给 mall-app 的下单接口注入「响应迟到」：latePercent 的下单请求在业务处理完、订单已经写进库之后，响应再晚 lateMs 才发出，
 * 超过 App 的超时（timeoutMs）。App 超时后重试：对照组每次重试换一个新的 Idempotency-Key（常见的客户端写法错误），
 * 实验组整个「下单意图」用同一个键。每个实验用户只有一次下单意图，事后查他的订单列表，数一下这次意图实际生成了几单。
 *
 * <p>输出 intents.csv：「生成了几单、App 最后看到的状态码」→ 用户数。注意订单服务的下单频控（同一 SKU 待支付最多 2 单）
 * 会把重复单封顶在 2，对照组看到的是「被频控兜住了一部分」后的结果。
 *
 * <pre>
 * 参数（-D）                     默认                     说明
 * baseUrl / ha.appUrl            :9000 / :8080            网关；mall-app 业务端口（注入故障用）
 * ha.jwtSecret                   必填
 * ha.firstUserId                 按当前时间取一段新用户    每轮要用没下过单的用户，默认按时间错开（每轮最多 1000 个用户）
 * ha.rps / ha.durationSec        5 / 60                   每秒新的下单意图数、持续时长
 * ha.latePercent / ha.lateMs     30 / 2500                多少比例的下单响应迟到、迟到多久
 * ha.timeoutMs                   1500                     App 的超时
 * ha.retry.sameKey               true                     重试时是否沿用同一个 Idempotency-Key
 * ha.retry.*                     见 ClientRetryPolicy      本实验 mode 默认 immediate、最多 3 次
 * ha.settleSec                   5                        重试结束后等多久再查订单（让迟到的请求在服务端处理完）
 * </pre>
 */
public class RetryIdempotencySimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(RetryIdempotencySimulation.class);

    private final HaConfig cfg = new HaConfig("b-retry-idempotency");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("baseUrl", "http://localhost:9000");
    private final String appUrl = cfg.str("ha.appUrl", "http://localhost:8080");
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final long firstUserId =
            cfg.longValue("ha.firstUserId", 950_000_000L + (System.currentTimeMillis() / 1000 % 40_000) * 1_000);
    private final List<String> skus = cfg.list("ha.skus", "20001,20002,20003");
    private final double rps = cfg.dbl("ha.rps", 5);
    private final int durationSec = cfg.integer("ha.durationSec", 60);
    private final int latePercent = cfg.integer("ha.latePercent", 30);
    private final int lateMs = cfg.integer("ha.lateMs", 2500);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 1500));
    private final boolean sameKey = cfg.bool("ha.retry.sameKey", true);
    private final int settleSec = cfg.integer("ha.settleSec", 5);
    private final ClientRetryPolicy retry = ClientRetryPolicy.from(cfg, "immediate");

    /** 「生成了几单 / App 最后看到的状态」→ 用户数。 */
    private final Map<String, AtomicInteger> intents = new ConcurrentHashMap<>();

    private final ScenarioBuilder intent = scenario("下单意图")
            .feed(tokens.circularUsers(firstUserId, (int) Math.ceil(rps * durationSec)))
            .exec(s -> HaChains.newIdempotencyKey(HaChains.pickSku(s, skus)))
            .exec(HaChains.call(
                    "POST /api/orders",
                    n -> http(n).post("/api/orders")
                            .header("Authorization", "Bearer #{token}")
                            .header("Idempotency-Key", "#{idemKey}")
                            .body(StringBody(HaChains::placeOrderBody)),
                    List.of(200, 201, 409),
                    timeout,
                    retry,
                    timeline,
                    s -> sameKey ? s : HaChains.newIdempotencyKey(s)))
            .exec(s -> s.set("orderLastStatus", s.getInt("ha_lastStatus"))) // 下一步核对请求会覆盖 ha_lastStatus，先存下来
            .exec(pause(Duration.ofSeconds(settleSec)))
            .exec(HaChains.call(
                    "核对：GET /api/orders",
                    n -> http(n).get("/api/orders?size=50")
                            .header("Authorization", "Bearer #{token}")
                            .check(jsonPath("$.items[*].orderNo").count().saveAs("ha_orderCount")),
                    List.of(200),
                    Duration.ofSeconds(5),
                    timeline))
            .exec(this::countIntent);

    private final ScenarioBuilder chaos = scenario("故障注入")
            .exec(HaChains.putFault(
                    appUrl,
                    "b2-order-late",
                    "{\"method\":\"POST\",\"path\":\"/api/orders\",\"responseDelayMs\":" + lateMs + ",\"delayPercent\":"
                            + latePercent + ",\"ttlSeconds\":" + (durationSec + 600) + "}",
                    "注入：" + latePercent + "% 的下单响应迟到 " + lateMs + "ms",
                    timeline))
            .exec(pause(Duration.ofSeconds(durationSec + settleSec + 10)))
            .exec(HaChains.deleteFault(appUrl, "b2-order-late", "撤销：下单响应恢复", timeline));

    {
        log.info("实验 B2 参数：{}", cfg.used());
        setUp(
                        chaos.injectOpen(atOnceUsers(1)),
                        intent.injectOpen(
                                nothingFor(Duration.ofSeconds(2)),
                                constantUsersPerSec(rps).during(Duration.ofSeconds(durationSec))))
                .protocols(HaChains.protocol(baseUrl))
                .maxDuration(Duration.ofSeconds(durationSec + 300));
    }

    private Session countIntent(Session s) {
        int created = s.contains("ha_orderCount") ? s.getInt("ha_orderCount") : -1; // -1：核对请求本身失败了
        int lastStatus = s.contains("orderLastStatus") ? s.getInt("orderLastStatus") : 0;
        intents.computeIfAbsent(created + "," + lastStatus, k -> new AtomicInteger())
                .incrementAndGet();
        return s;
    }

    @Override
    public void before() {
        cfg.markStarted();
    }

    @Override
    public void after() {
        Path dir = cfg.outDir();
        try {
            timeline.write(dir, cfg.usedAsCsvLines());
            List<String> lines = new ArrayList<>();
            lines.add("orders_created,last_status,users");
            new TreeMap<>(intents).forEach((k, v) -> lines.add(k + "," + v.get()));
            Files.write(dir.resolve("intents.csv"), lines, StandardCharsets.UTF_8);
            log.info("每个下单意图生成的订单数（订单数,最后状态 → 用户数）：{}", intents);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
