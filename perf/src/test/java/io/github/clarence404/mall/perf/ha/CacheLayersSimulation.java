package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实验 F：多级缓存——没有缓存、只有 Redis、两级缓存各能撑到每秒多少次详情；热点 key 失效时的击穿；不存在的 SKU 的穿透
 * （experiments/f-cache-layers）。
 *
 * <p>只有一路流量：经网关匿名访问商品详情 {@code GET /api/products/{skuId}}（网关的 anonymous 路由，没有按用户的限流）。
 * 开放模型。缓存开不开、单飞开不开、空值缓不缓存，都由 mall-app 的启动参数（{@code mall.demo.detail-cache.*}）决定，本场景只管发请求。
 *
 * <ul>
 *   <li>{@code ha.mode=stair}（默认）：在 ha.skus 的前 skuCount 个 SKU 上浏览，hotShare 比例的请求给第一个（最热的），其余平均分；
 *       速率从 startRps 起每 stepSec 秒加 stepRps，加到 maxRps；每一级开始时在 events.csv 记一笔
 *   <li>{@code ha.mode=breakdown}（击穿）：detailRps 全部打第一个 SKU，持续 durationSec 秒；每隔 expireEverySec 秒调一次运营后台的
 *       「手动清缓存」接口（Redis 里的直接删，各实例本机的经广播删），每次在 events.csv 记一笔
 *   <li>{@code ha.mode=penetration}（穿透）：detailRps 全部打不存在的 SKU，期望 404。{@code ha.missIds=repeat} 在 100 个固定 ID 里循环，
 *       {@code random} 每个请求一个新的随机 ID
 * </ul>
 *
 * <p>三种模式前面都有一段预热（warmupSec 秒、每秒 warmupRps 次，请求名带「(预热)」）：服务刚起来时头几秒的请求很慢，不预热的话
 * 第一级的数据是废的。预热请求的都是真实存在的 SKU（stair 按同样的偏斜、breakdown 只打热点、penetration 打 ha.skus），
 * 不会提前把不存在的 ID 写进空值缓存。
 *
 * <pre>
 * 参数（-D）                                          默认                    说明
 * baseUrl / ha.appUrl                                 :9000 / :8080           网关；mall-app 业务端口（清缓存接口不经网关）
 * ha.jwtSecret                                        必填                    只有 breakdown 用（清缓存接口要 ADMIN 角色的令牌）
 * ha.skus                                             20001,20002,20003       存在的 SKU，第一个是热点
 * ha.skuCount                                         0                       只用前几个，0 表示全部
 * ha.hotShare                                         0.8                     stair：给最热 SKU 的请求比例
 * ha.mode                                             stair                   stair / breakdown / penetration
 * ha.startRps / ha.stepRps / ha.stepSec / ha.maxRps   200 / 200 / 60 / 1600   stair 的阶梯
 * ha.detailRps                                        300（穿透 200）         breakdown、penetration 的固定速率
 * ha.durationSec                                      180                     breakdown、penetration 的时长（不含预热）
 * ha.expireEverySec                                   20                      breakdown：每隔多少秒让热点 SKU 的两级缓存失效一次
 * ha.missIds                                          repeat                  penetration：repeat 或 random
 * ha.missBase / ha.missCount                          9000000000000 / 100     不存在的 ID 从哪里起；repeat 用多少个
 * ha.warmupSec / ha.warmupRps                         30 / 50                 预热；warmupSec 为 0 表示不预热
 * ha.timeoutMs                                        3000                    客户端超时（与网关的 3 秒一致），超过记为状态 0
 * </pre>
 */
public class CacheLayersSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(CacheLayersSimulation.class);

    /** 详情请求的名字；预热的加 {@link #WARMUP_SUFFIX}，打不存在 SKU 的加 {@link #MISSING_SUFFIX}。 */
    static final String DETAIL = "GET /api/products/{skuId}";

    static final String WARMUP_SUFFIX = " (预热)";
    static final String MISSING_SUFFIX = " (不存在)";

    private final HaConfig cfg = new HaConfig("f-cache-layers");
    private final Timeline timeline = new Timeline();

    private final String baseUrl = cfg.str("baseUrl", "http://localhost:9000");
    private final String appUrl = cfg.str("ha.appUrl", "http://localhost:8080");
    private final ExperimentTokens tokens = new ExperimentTokens(
            cfg.required("ha.jwtSecret", "见 experiments/README.md「令牌」"), "mall-evolution", Duration.ofHours(2));
    private final List<String> allSkus = cfg.list("ha.skus", "20001,20002,20003");
    private final int skuCount = cfg.integer("ha.skuCount", 0);
    private final double hotShare = cfg.dbl("ha.hotShare", 0.8);
    private final String mode = cfg.str("ha.mode", "stair");
    private final double startRps = cfg.dbl("ha.startRps", 200);
    private final double stepRps = cfg.dbl("ha.stepRps", 200);
    private final int stepSec = cfg.integer("ha.stepSec", 60);
    private final double maxRps = cfg.dbl("ha.maxRps", 1600);
    private final double detailRps = cfg.dbl("ha.detailRps", "penetration".equals(mode) ? 200 : 300);
    private final int durationSec = cfg.integer("ha.durationSec", 180);
    private final int expireEverySec = cfg.integer("ha.expireEverySec", 20);
    private final String missIds = cfg.str("ha.missIds", "repeat");
    private final long missBase = cfg.longValue("ha.missBase", MissingSkuIds.DEFAULT_BASE);
    private final int missCount = cfg.integer("ha.missCount", 100);
    private final int warmupSec = cfg.integer("ha.warmupSec", 30);
    private final double warmupRps = cfg.dbl("ha.warmupRps", 50);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 3000));
    /** 调后台清缓存接口的用户：不在 t_user 里，接口只看令牌里的角色。 */
    private final long adminUserId = cfg.longValue("ha.adminUserId", 900_000_000L);

    private final List<String> skus =
            skuCount > 0 && skuCount < allSkus.size() ? allSkus.subList(0, skuCount) : allSkus;
    private final String hotSku = skus.getFirst();

    /** stair：按偏斜挑；breakdown：只有热点一个；penetration 的预热：在存在的 SKU 里按同样的偏斜挑。 */
    private final SkewedPicker existing =
            "breakdown".equals(mode) ? new SkewedPicker(List.of(hotSku), 1) : new SkewedPicker(skus, hotShare);

    private final LongSupplier missing = switch (missIds) {
        case "repeat" -> MissingSkuIds.repeat(missBase, missCount);
        case "random" -> MissingSkuIds.random(missBase, missCount);
        default -> throw new IllegalArgumentException("ha.missIds 只能是 repeat 或 random，实际：" + missIds);
    };

    /** breakdown、penetration 就是只有一级的阶梯：三种模式共用同一套注入。 */
    private final StairSchedule schedule = switch (mode) {
        case "stair" -> StairSchedule.of(startRps, stepRps, stepSec, maxRps);
        case "breakdown", "penetration" -> StairSchedule.of(detailRps, detailRps, durationSec, detailRps);
        default -> throw new IllegalArgumentException("ha.mode 只能是 stair、breakdown 或 penetration，实际：" + mode);
    };

    {
        if ("breakdown".equals(mode) && expireEverySec <= 0) {
            throw new IllegalArgumentException("ha.expireEverySec 必须大于 0，实际：" + expireEverySec);
        }
        log.info("实验 F 参数：{}", cfg.used());
        log.info(
                "实验 F 日程：模式 {}，预热 {} 秒，之后 {} 级，共 {} 秒、约 {} 次请求，最高 {}/s；SKU {}，热点 {}",
                mode,
                warmupSec,
                schedule.levels().size(),
                schedule.totalSec(),
                schedule.totalRequests(),
                schedule.peakRps(),
                skus,
                hotSku);
        List<OpenInjectionStep> steps = new ArrayList<>();
        if (warmupSec > 0) {
            steps.add(nothingFor(Duration.ofSeconds(warmupSec)));
        }
        schedule.levels()
                .forEach(l -> steps.add(constantUsersPerSec(l.rps()).during(Duration.ofSeconds(l.durationSec()))));
        List<PopulationBuilder> populations = new ArrayList<>();
        populations.add(
                ("penetration".equals(mode) ? browseMissing() : browseExisting("浏览商品详情", DETAIL)).injectOpen(steps));
        populations.add(control().injectOpen(atOnceUsers(1)));
        if (warmupSec > 0 && warmupRps > 0) {
            populations.add(browseExisting("预热", DETAIL + WARMUP_SUFFIX)
                    .injectOpen(constantUsersPerSec(warmupRps).during(Duration.ofSeconds(warmupSec))));
        }
        setUp(populations)
                .protocols(HaChains.protocol(baseUrl))
                .maxDuration(Duration.ofSeconds(warmupSec + schedule.totalSec() + 30L));
    }

    /** 访问存在的 SKU：只有 200 算成功。 */
    private ScenarioBuilder browseExisting(String scenarioName, String requestName) {
        return scenario(scenarioName)
                .exec(s ->
                        s.set("skuId", existing.pick(ThreadLocalRandom.current().nextDouble())))
                .exec(HaChains.call(
                        requestName, n -> http(n).get("/api/products/#{skuId}"), List.of(200), timeout, timeline));
    }

    /** 访问不存在的 SKU：期望 404（PRODUCT_NOT_FOUND）；别的状态码（5xx、超时）才是出了问题。 */
    private ScenarioBuilder browseMissing() {
        return scenario("访问不存在的 SKU")
                .exec(s -> s.set("skuId", String.valueOf(missing.getAsLong())))
                .exec(HaChains.call(
                        DETAIL + MISSING_SUFFIX,
                        n -> http(n).get("/api/products/#{skuId}"),
                        List.of(404),
                        timeout,
                        timeline));
    }

    /** 实验控制：在 events.csv 记下预热、各级（或稳态）的开始时刻；breakdown 另外每隔 expireEverySec 秒让热点 SKU 失效一次。 */
    private ScenarioBuilder control() {
        ChainBuilder c = exec(s -> s);
        if (warmupSec > 0) {
            c = c.exec(mark("预热：详情 " + rate(warmupRps) + "/s，持续 " + warmupSec + " 秒"))
                    .exec(pause(Duration.ofSeconds(warmupSec)));
        }
        switch (mode) {
            case "stair" -> {
                for (StairSchedule.Level l : schedule.levels()) {
                    c = c.exec(mark("第 " + l.index() + " 级：详情 " + rate(l.rps()) + "/s，持续 " + l.durationSec() + " 秒"))
                            .exec(pause(Duration.ofSeconds(l.durationSec())));
                }
            }
            case "breakdown" -> {
                c = c.exec(mark("击穿开始：详情 " + rate(detailRps) + "/s 全部打 SKU " + hotSku + "，每 " + expireEverySec
                        + " 秒失效一次，持续 " + durationSec + " 秒"));
                String adminToken = tokens.mintAdmin(adminUserId);
                int elapsed = 0;
                int n = 0;
                while (elapsed + expireEverySec < durationSec) {
                    n++;
                    elapsed += expireEverySec;
                    c = c.exec(pause(Duration.ofSeconds(expireEverySec))).exec(expire(n, adminToken));
                }
                c = c.exec(pause(Duration.ofSeconds(Math.max(0, durationSec - elapsed))));
            }
            default -> // penetration
                c = c.exec(mark("穿透开始：不存在的 SKU " + rate(detailRps) + "/s，"
                                + ("repeat".equals(missIds) ? missCount + " 个固定 ID 循环" : "每个请求一个随机 ID")
                                + "，持续 " + durationSec + " 秒"))
                        .exec(pause(Duration.ofSeconds(durationSec)));
        }
        return scenario("实验控制").exec(c).exec(mark("压测结束"));
    }

    /**
     * 让热点 SKU 的两级缓存失效：调运营后台的手动清缓存接口（和运营改价后手动刷新走的是同一条路——删 Redis 里的 key，
     * 再广播让每个实例删本机的）。只有返回 204 才记事件；没清成功的话报告里这个请求是 KO，events.csv 里也少一笔，看得出来。
     */
    private ChainBuilder expire(int n, String adminToken) {
        return exec(http("失效：清 SKU 缓存")
                        .post(appUrl + "/api/admin/products/" + hotSku + "/cache-evict")
                        .header("Authorization", "Bearer " + adminToken)
                        .check(status().is(204).saveAs("evictStatus")))
                .doIf(s -> s.contains("evictStatus"))
                .then(exec(s -> {
                    timeline.mark("第 " + n + " 次失效：SKU " + hotSku + " 的两级缓存已清");
                    return s.remove("evictStatus");
                }));
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
