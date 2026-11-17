package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实验 B1：重试风暴——同一个 20 秒的小故障，重试策略决定系统能不能自己恢复（experiments/b-retry-storm）。
 *
 * <p>链路：App 轮询「支付状态」{@code GET /api/orders/{orderNo}/payment} → 网关 → mall-app（PaymentQueryClient：重试 + 熔断 + 舱壁）
 * → payment-service {@code GET /internal/payments/{orderNo}}。用故障注入给支付查询建一个容量有限的瓶颈（并发上限 capConcurrent、
 * 每个请求占用 capServiceMs，容量约 capConcurrent / capServiceMs），平时负载约为容量的六成；第 hiccupAtSec 秒把占用时间调到
 * hiccupServiceMs（容量掉到十分之一），持续 hiccupSec 秒后调回。瓶颈前排队的请求在调用方超时放弃后仍会被处理，这就是重试风暴里
 * 「白干的活」。
 *
 * <p>三个阶段：先用 prepareUsers 个实验用户各下一单（拿到能查的订单号）→ 轮询开始，同时故障时间线开始计时。
 * 客户端（本场景）的重试策略见 {@link ClientRetryPolicy}，服务端 mall-app 的重试与熔断由启动参数决定（experiments/b-retry-storm/*.properties）。
 *
 * <pre>
 * 参数（-D）                     默认                    说明
 * baseUrl / ha.paymentUrl        :9000 / :8083           网关；支付服务业务端口（注入故障用）
 * ha.jwtSecret                   必填
 * ha.prepareUsers / ha.prepareRps  600 / 50              准备阶段下单的用户数与速率
 * ha.pollRps / ha.durationSec    150 / 300               每秒轮询数（首次请求，不含重试）与轮询时长
 * ha.capConcurrent / ha.capServiceMs / ha.hiccupServiceMs  10 / 40 / 400   瓶颈参数
 * ha.hiccupAtSec / ha.hiccupSec  60 / 20                 小故障何时开始、持续多久（从轮询开始算）
 * ha.timeoutMs                   2000                    App 的超时
 * ha.retry.*                     见 ClientRetryPolicy     客户端重试策略
 * </pre>
 */
public class RetryStormSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(RetryStormSimulation.class);

    private final HaConfig cfg = new HaConfig("b-retry-storm");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("baseUrl", "http://localhost:9000");
    private final String paymentUrl = cfg.str("ha.paymentUrl", "http://localhost:8083");
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final long firstUserId = cfg.longValue("ha.firstUserId", 900_000_001L);
    private final List<String> skus = cfg.list("ha.skus", "20001,20002,20003");
    private final int prepareUsers = cfg.integer("ha.prepareUsers", 600);
    private final double prepareRps = cfg.dbl("ha.prepareRps", 50);
    private final double pollRps = cfg.dbl("ha.pollRps", 150);
    private final int durationSec = cfg.integer("ha.durationSec", 300);
    private final int capConcurrent = cfg.integer("ha.capConcurrent", 10);
    private final int capServiceMs = cfg.integer("ha.capServiceMs", 40);
    private final int hiccupServiceMs = cfg.integer("ha.hiccupServiceMs", 400);
    private final int hiccupAtSec = cfg.integer("ha.hiccupAtSec", 60);
    private final int hiccupSec = cfg.integer("ha.hiccupSec", 20);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 2000));
    private final ClientRetryPolicy retry = ClientRetryPolicy.from(cfg);

    /** 准备阶段下好的订单：(令牌, 订单号)。轮询阶段从里面随机取。 */
    private final List<Map<String, Object>> orders = new CopyOnWriteArrayList<>();

    private final ScenarioBuilder prepare = scenario("准备：每人下一单")
            .feed(tokens.circularUsers(firstUserId, prepareUsers))
            .exec(s -> HaChains.newIdempotencyKey(HaChains.pickSku(s, skus)))
            .exec(HaChains.call(
                    "准备：POST /api/orders",
                    n -> http(n).post("/api/orders")
                            .header("Authorization", "Bearer #{token}")
                            .header("Idempotency-Key", "#{idemKey}")
                            .body(StringBody(HaChains::placeOrderBody))
                            .check(jsonPath("$.orderNo").optional().saveAs("orderNo")),
                    List.of(200, 201),
                    Duration.ofSeconds(5),
                    timeline))
            .exec(s -> {
                if (s.contains("orderNo")) {
                    orders.add(Map.of("token", s.getString("token"), "orderNo", s.getString("orderNo")));
                }
                return s;
            });

    private final ScenarioBuilder poll = scenario("轮询支付状态")
            .feed(preparedOrders())
            .exec(HaChains.call(
                    "GET /api/orders/{orderNo}/payment",
                    n -> http(n).get("/api/orders/#{orderNo}/payment").header("Authorization", "Bearer #{token}"),
                    List.of(200),
                    timeout,
                    retry,
                    timeline));

    private final ScenarioBuilder chaos = scenario("故障注入")
            .exec(HaChains.putFault(paymentUrl, "b-pay-capacity", spec(capServiceMs), "瓶颈：支付查询容量（平时）", timeline))
            .exec(pause(Duration.ofSeconds(hiccupAtSec)))
            .exec(HaChains.putFault(paymentUrl, "b-pay-capacity", spec(hiccupServiceMs), "小故障开始：支付查询变慢", timeline))
            .exec(pause(Duration.ofSeconds(hiccupSec)))
            .exec(HaChains.putFault(paymentUrl, "b-pay-capacity", spec(capServiceMs), "小故障结束：支付查询恢复", timeline))
            .exec(pause(Duration.ofSeconds(Math.max(1, durationSec - hiccupAtSec - hiccupSec))))
            .exec(HaChains.deleteFault(paymentUrl, "b-pay-capacity", "撤销瓶颈", timeline));

    {
        log.info("实验 B1 参数：{}", cfg.used());
        setUp(prepare.injectOpen(constantUsersPerSec(prepareRps)
                                .during(Duration.ofMillis((long) (prepareUsers / prepareRps * 1000))))
                        .andThen(
                                poll.injectOpen(constantUsersPerSec(pollRps).during(Duration.ofSeconds(durationSec))),
                                chaos.injectOpen(atOnceUsers(1))))
                .protocols(HaChains.protocol(baseUrl))
                .maxDuration(Duration.ofSeconds(durationSec + 300));
    }

    private String spec(int serviceMs) {
        return "{\"method\":\"GET\",\"path\":\"/internal/payments/*\",\"maxConcurrent\":" + capConcurrent
                + ",\"delayMs\":" + serviceMs + ",\"ttlSeconds\":" + (durationSec + 600) + "}";
    }

    /** 随机取一个准备好的订单。准备阶段一单都没下成时，压测没有意义，直接报错停下。 */
    private Iterator<Map<String, Object>> preparedOrders() {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Map<String, Object> next() {
                if (orders.isEmpty()) {
                    throw new IllegalStateException("准备阶段没有下成任何订单，检查库存是否已补足、服务是否正常");
                }
                return orders.get(ThreadLocalRandom.current().nextInt(orders.size()));
            }
        };
    }

    @Override
    public void before() {
        cfg.markStarted();
    }

    @Override
    public void after() {
        log.info("客户端重试预算：{}；准备阶段下单 {} 个", retry.budget().summary(), orders.size());
        timeline.mark("客户端重试预算 " + retry.budget().summary());
        try {
            timeline.write(cfg.outDir(), cfg.usedAsCsvLines());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
