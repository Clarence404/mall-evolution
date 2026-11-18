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
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实验 G：异步削峰——下单量突然翻几倍时，接口照常返回，多出来的后续处理在消息队列里排队，消费者按自己的速度慢慢消化
 * （experiments/g-async-peak）。
 *
 * <p>只有一路流量：经网关下单，平时 baseRps，第 burstAtSec 秒起升到 burstRps、持续 burstSec 秒，再回到 baseRps，直到 durationSec；
 * 升降一步到位。开放模型。每个请求换一个新的实验用户（网关每人每秒 1 单、订单服务同一 SKU 待支付最多 2 单）和新的 Idempotency-Key，
 * 在 ha.skus 里随机挑一个 SKU。消费得快还是慢由服务的启动参数决定（{@code mall.demo.consume-delay-ms}、
 * {@code mall.rocketmq.consumption-threads}），本场景不变。
 *
 * <p>下单之后发生的事不在这里发起，但时间上要对得上：订单不支付，payTimeoutSec 秒后（demo 环境 60 秒）order-timeout 定时消息到期，
 * mall-app 关单并发出 order-cancelled，库存服务消费它、释放预占。所以消费端看到的高峰比下单的高峰晚 payTimeoutSec 秒——
 * events.csv 里「关单高峰预计到达 / 结束」两笔就是按这个算的，事后切数据用。
 *
 * <p>压测流量结束后场景不马上退出，再空等 drainSec 秒：最后一批订单还要 payTimeoutSec 秒后才关单，消费慢的那几组还有积压要消化，
 * 这段时间实验脚本的逐秒采样（metrics.csv）一直在记，消费延迟回落的过程才看得全。
 *
 * <pre>
 * 参数（-D）                                  默认                  说明
 * baseUrl                                     http://localhost:9000 网关
 * ha.jwtSecret                                必填
 * ha.skus                                     20001,20002,20003     下单的 SKU（脚本事先补足库存）
 * ha.firstUserId                              按当前时间取一段      实验用户从这个 ID 起依次往后取，一轮之内不重复
 * ha.baseRps / ha.burstRps                    20 / 60               平时与高峰的每秒下单数
 * ha.burstAtSec / ha.burstSec                 60 / 60               高峰从第几秒开始、持续多久（都不含预热）
 * ha.durationSec                              360                   压测流量的总时长（不含预热与收尾的空等）
 * ha.warmupSec / ha.warmupRps                 30 / 20               预热多久、每秒几单；warmupSec 为 0 表示不预热
 * ha.payTimeoutSec                            60                    未支付多久关单（只用来在 events.csv 里标出关单高峰的预计时刻）
 * ha.drainSec                                 240                   压测流量结束后再空等多久
 * ha.timeoutMs                                3000                  客户端超时（与网关的 3 秒一致），超过记为状态 0
 * </pre>
 */
public class AsyncPeakSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(AsyncPeakSimulation.class);

    private final HaConfig cfg = new HaConfig("g-async-peak");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("baseUrl", "http://localhost:9000");
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final List<String> skus = cfg.list("ha.skus", "20001,20002,20003");
    private final long firstUserId =
            cfg.longValue("ha.firstUserId", ExperimentTokens.firstUserIdByTime(System.currentTimeMillis()));
    private final BurstSchedule schedule = new BurstSchedule(
            cfg.dbl("ha.baseRps", 20),
            cfg.dbl("ha.burstRps", 60),
            cfg.integer("ha.burstAtSec", 60),
            cfg.integer("ha.burstSec", 60),
            cfg.integer("ha.durationSec", 360));
    private final int warmupSec = cfg.integer("ha.warmupSec", 30);
    private final double warmupRps = cfg.dbl("ha.warmupRps", 20);
    private final int payTimeoutSec = cfg.integer("ha.payTimeoutSec", 60);
    private final int drainSec = cfg.integer("ha.drainSec", 240);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 3000));

    /** 预热与正式压测共用一个取号器：两段用到的实验用户接着往后取，互不重复。 */
    private final Iterator<Map<String, Object>> users = tokens.sequentialUsers(firstUserId);

    private final ScenarioBuilder warmup = placeOrder("预热", "POST /api/orders (预热)");
    private final ScenarioBuilder order = placeOrder("下单", "POST /api/orders");

    {
        log.info("实验 G 参数：{}", cfg.used());
        log.info(
                "实验 G 日程：预热 {} 秒，之后 {} 秒（第 {}–{} 秒高峰），约 {} 单，结束后再等 {} 秒",
                warmupSec,
                schedule.durationSec(),
                schedule.burstAtSec(),
                schedule.burstEndSec(),
                schedule.totalRequests(),
                drainSec);
        List<OpenInjectionStep> steps = new ArrayList<>();
        if (warmupSec > 0) {
            steps.add(nothingFor(Duration.ofSeconds(warmupSec)));
        }
        schedule.phases()
                .forEach(p -> steps.add(constantUsersPerSec(p.rps()).during(Duration.ofSeconds(p.durationSec()))));
        List<PopulationBuilder> populations = new ArrayList<>();
        populations.add(order.injectOpen(steps));
        populations.add(control().injectOpen(atOnceUsers(1)));
        if (warmupSec > 0 && warmupRps > 0) {
            populations.add(warmup.injectOpen(constantUsersPerSec(warmupRps).during(Duration.ofSeconds(warmupSec))));
        }
        setUp(populations)
                .protocols(HaChains.protocol(baseUrl))
                .maxDuration(Duration.ofSeconds(warmupSec + schedule.durationSec() + drainSec + 30L));
    }

    private ScenarioBuilder placeOrder(String scenarioName, String requestName) {
        return scenario(scenarioName)
                .feed(users)
                .exec(s -> HaChains.newIdempotencyKey(HaChains.pickSku(s, skus)))
                .exec(HaChains.call(
                        requestName,
                        n -> http(n).post("/api/orders")
                                .header("Authorization", "Bearer #{token}")
                                .header("Idempotency-Key", "#{idemKey}")
                                .body(StringBody(HaChains::placeOrderBody)),
                        // 只有下单成功才算 OK：库存事先备足、用户不重复，出现 409 说明前提没满足，要看得见
                        List.of(200, 201),
                        timeout,
                        timeline));
    }

    /** 一个要在 events.csv 里记下的时刻。 */
    private record Mark(int atSec, String event) {}

    /**
     * 实验控制：按时间先后在 events.csv 记下各个时刻（和 timeline.csv 同一时间轴），最后空等到 drainSec 结束，
     * 让场景活着、采样继续。时刻都从压测开始算，含预热。
     */
    private ScenarioBuilder control() {
        int t0 = warmupSec;
        int end = t0 + schedule.durationSec();
        List<Mark> marks = new ArrayList<>();
        if (warmupSec > 0) {
            marks.add(new Mark(0, "预热：下单 " + rate(warmupRps) + "/s，持续 " + warmupSec + " 秒"));
        }
        marks.add(new Mark(t0, "平时流量开始：下单 " + rate(schedule.baseRps()) + "/s"));
        if (schedule.burstSec() > 0) {
            marks.add(new Mark(
                    t0 + schedule.burstAtSec(),
                    "高峰开始：下单 " + rate(schedule.burstRps()) + "/s，持续 " + schedule.burstSec() + " 秒"));
            marks.add(new Mark(t0 + schedule.burstEndSec(), "高峰结束：下单回到 " + rate(schedule.baseRps()) + "/s"));
            marks.add(new Mark(
                    t0 + schedule.burstAtSec() + payTimeoutSec, "关单高峰预计到达：高峰期的订单开始超时关单（下单后 " + payTimeoutSec + " 秒）"));
            marks.add(new Mark(t0 + schedule.burstEndSec() + payTimeoutSec, "关单高峰预计结束"));
        }
        marks.add(new Mark(end, "压测流量结束，再等 " + drainSec + " 秒让关单与消费收尾"));
        marks.add(new Mark(end + payTimeoutSec, "最后一批订单预计到期关单"));
        marks.add(new Mark(end + drainSec, "压测结束"));
        marks.removeIf(m -> m.atSec() > end + drainSec);
        marks.sort(Comparator.comparingInt(Mark::atSec)); // 稳定排序：同一时刻的保持加入的先后

        ChainBuilder c = exec(s -> s);
        int now = 0;
        for (Mark m : marks) {
            if (m.atSec() > now) {
                c = c.exec(pause(Duration.ofSeconds(m.atSec() - now)));
                now = m.atSec();
            }
            c = c.exec(mark(m.event()));
        }
        return scenario("实验控制").exec(c);
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
