package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.incrementUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实验 D：突发流量超过容量——入口限流与降级能不能保住下单（experiments/d-overload）。
 *
 * <p>两路流量：核心的下单（orderRps，始终不变）和匿名浏览（商品详情 70%、猜你喜欢 30%，走网关的 anonymous 路由）。
 * 两种模式：
 *
 * <ul>
 *   <li>{@code ha.mode=probe}：浏览量阶梯上升（startRps 起，每级加 stepRps、持续 stepSec 秒，共 steps 级），找出 mall-app 的拐点——
 *       下单 P99 超过 800ms 或开始出错时的浏览量，作为后面限流阈值的依据
 *   <li>{@code ha.mode=burst}（默认）：平时 baseRps，第 burstAtSec 秒起浏览量升到 burstRps、持续 burstSec 秒，再回落
 * </ul>
 *
 * <p>容量模型：本机跑压测和四个服务，CPU 是共用的，拐点受机器影响。想让结果在哪台机器上都可复现，打开 {@code ha.appCapConcurrent}：
 * 给 mall-app 的全部 /api/** 请求注入一个统一的容量上限（并发 appCapConcurrent、每个请求占用 appCapServiceMs，
 * 容量约 appCapConcurrent / appCapServiceMs），模拟「mall-app 就这么大的处理能力」，下单和浏览共用它。
 * 降级开关（猜你喜欢改固定列表）可以在 degradeAtSec 秒打开，看它省下了什么；入口限流由网关启动参数决定（见 *.properties）。
 *
 * <pre>
 * 参数（-D）                               默认                说明
 * baseUrl / ha.appUrl                      :9000 / :8080       网关；mall-app 业务端口（注入容量模型、拨降级开关）
 * ha.jwtSecret                             必填
 * ha.mode                                  burst               probe 或 burst
 * ha.orderRps                              20                  每秒下单
 * ha.baseRps / ha.burstRps                 200 / 1500          平时与高峰的浏览量
 * ha.burstAtSec / ha.burstSec / ha.durationSec  60 / 180 / 360
 * ha.startRps / ha.stepRps / ha.stepSec / ha.steps  100 / 200 / 60 / 10   probe 模式的阶梯
 * ha.appCapConcurrent / ha.appCapServiceMs  0 / 20             容量模型，0 表示不注入
 * ha.degradeAtSec                          -1                  第几秒打开降级开关（-1 不打开）
 * ha.timeoutMs                             3000                客户端超时（与网关的 3 秒一致）
 * </pre>
 */
public class OverloadSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(OverloadSimulation.class);

    private final HaConfig cfg = new HaConfig("d-overload");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("baseUrl", "http://localhost:9000");
    private final String appUrl = cfg.str("ha.appUrl", "http://localhost:8080");
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final int users = cfg.integer("ha.users", 20_000);
    private final long firstUserId = cfg.longValue("ha.firstUserId", 900_000_001L);
    private final List<String> skus = cfg.list("ha.skus", "20001,20002,20003");
    private final String mode = cfg.str("ha.mode", "burst");
    private final double orderRps = cfg.dbl("ha.orderRps", 20);
    private final double baseRps = cfg.dbl("ha.baseRps", 200);
    private final double burstRps = cfg.dbl("ha.burstRps", 1500);
    private final int burstAtSec = cfg.integer("ha.burstAtSec", 60);
    private final int burstSec = cfg.integer("ha.burstSec", 180);
    private final int durationSec = cfg.integer("ha.durationSec", 360);
    private final double startRps = cfg.dbl("ha.startRps", 100);
    private final double stepRps = cfg.dbl("ha.stepRps", 200);
    private final int stepSec = cfg.integer("ha.stepSec", 60);
    private final int steps = cfg.integer("ha.steps", 10);
    private final int appCapConcurrent = cfg.integer("ha.appCapConcurrent", 0);
    private final int appCapServiceMs = cfg.integer("ha.appCapServiceMs", 20);
    private final int degradeAtSec = cfg.integer("ha.degradeAtSec", -1);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 3000));

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

    private final ScenarioBuilder browse = scenario("浏览")
            .exec(s -> HaChains.pickSku(s, skus))
            .exec(randomSwitch()
                    .on(
                            percent(70.0)
                                    .then(HaChains.call(
                                            "GET /api/products/{skuId}",
                                            n -> http(n).get("/api/products/#{skuId}"),
                                            List.of(200),
                                            timeout,
                                            timeline)),
                            percent(30.0)
                                    .then(HaChains.call(
                                            "GET /api/products/{skuId}/recommendations",
                                            n -> http(n).get("/api/products/#{skuId}/recommendations"),
                                            List.of(200),
                                            timeout,
                                            timeline))));

    {
        log.info("实验 D 参数：{}", cfg.used());
        Duration total = "probe".equals(mode)
                ? Duration.ofSeconds((long) steps * stepSec + (long) (steps - 1) * 5)
                : Duration.ofSeconds(durationSec);
        List<PopulationBuilder> populations = new ArrayList<>();
        populations.add(order.injectOpen(constantUsersPerSec(orderRps).during(total)));
        populations.add(browse.injectOpen(browseProfile()));
        populations.add(control(total).injectOpen(atOnceUsers(1)));
        setUp(populations).protocols(HaChains.protocol(baseUrl)).maxDuration(total.plusSeconds(120));
    }

    private OpenInjectionStep[] browseProfile() {
        if ("probe".equals(mode)) {
            return new OpenInjectionStep[] {
                incrementUsersPerSec(stepRps)
                        .times(steps)
                        .eachLevelLasting(Duration.ofSeconds(stepSec))
                        .separatedByRampsLasting(Duration.ofSeconds(5))
                        .startingFrom(startRps)
            };
        }
        int rest = Math.max(1, durationSec - burstAtSec - burstSec - 10);
        return new OpenInjectionStep[] {
            constantUsersPerSec(baseRps).during(Duration.ofSeconds(burstAtSec)),
            rampUsersPerSec(baseRps).to(burstRps).during(Duration.ofSeconds(5)),
            constantUsersPerSec(burstRps).during(Duration.ofSeconds(burstSec)),
            rampUsersPerSec(burstRps).to(baseRps).during(Duration.ofSeconds(5)),
            constantUsersPerSec(baseRps).during(Duration.ofSeconds(rest))
        };
    }

    /** 实验控制：注入容量模型、到点打开降级开关、结束时撤销容量模型并把降级开关拨回去。 */
    private ScenarioBuilder control(Duration total) {
        ChainBuilder c = exec(s -> {
            timeline.mark("probe".equals(mode) ? "阶梯开始" : "平时流量开始");
            return s;
        });
        if (appCapConcurrent > 0) {
            c = c.exec(HaChains.putFault(
                    appUrl,
                    "d-app-capacity",
                    "{\"path\":\"/api/**\",\"maxConcurrent\":" + appCapConcurrent + ",\"delayMs\":" + appCapServiceMs
                            + ",\"ttlSeconds\":" + (total.toSeconds() + 600) + "}",
                    "容量模型：mall-app 并发 " + appCapConcurrent + "、每请求 " + appCapServiceMs + "ms",
                    timeline));
        }
        long elapsed = 0;
        if (!"probe".equals(mode)) {
            if (degradeAtSec >= 0 && degradeAtSec < burstAtSec) {
                c = c.exec(pause(Duration.ofSeconds(degradeAtSec))).exec(degrade(false));
                elapsed = degradeAtSec;
            }
            c = c.exec(pause(Duration.ofSeconds(burstAtSec - elapsed))).exec(mark("高峰开始：浏览 " + burstRps + "/s"));
            elapsed = burstAtSec;
            if (degradeAtSec >= burstAtSec) {
                c = c.exec(pause(Duration.ofSeconds(degradeAtSec - elapsed))).exec(degrade(false));
                elapsed = degradeAtSec;
            }
            c = c.exec(pause(Duration.ofSeconds(Math.max(0, burstAtSec + burstSec + 10 - elapsed))))
                    .exec(mark("高峰结束"));
            elapsed = Math.max(elapsed, burstAtSec + burstSec + 10L);
        }
        c = c.exec(pause(Duration.ofSeconds(Math.max(1, total.toSeconds() - elapsed))));
        if (degradeAtSec >= 0) {
            c = c.exec(degrade(true));
        }
        if (appCapConcurrent > 0) {
            c = c.exec(HaChains.deleteFault(appUrl, "d-app-capacity", "撤销容量模型", timeline));
        }
        return scenario("实验控制").exec(c);
    }

    /** 拨降级开关：enabled=false 表示打开降级（猜你喜欢改为固定列表）。 */
    private ChainBuilder degrade(boolean recommendEnabled) {
        String event = recommendEnabled ? "降级关闭：猜你喜欢恢复" : "降级打开：猜你喜欢改为固定列表";
        return exec(http("降级开关").put(appUrl + "/internal/demo/degrade?recommendEnabled=" + recommendEnabled))
                .exec(mark(event));
    }

    private ChainBuilder mark(String event) {
        return exec(s -> {
            timeline.mark(event);
            return s;
        });
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
