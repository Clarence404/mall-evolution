package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.doSwitch;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.onCase;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Choice;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实验 J：数据访问——同一份数据上，SQL 的几种写法各差多少（experiments/j-data-access）。请求直接打 mall-app 的
 * /internal/demo/perf/**（不经网关、不要登录），三种模式由 ha.mode 选：
 *
 * <ul>
 *   <li>deep-paging：大客户（20 万单）的订单列表翻到不同深度，偏移量分页、延迟关联、游标分页各压一段
 *   <li>n-plus-one：最近 20 单带明细，逐单查明细、一次 IN、一次 JOIN 各压一段
 *   <li>batch-write：往 t_order_item 写一批行，逐条、事务里逐条、一条语句多行、JDBC 批量，各种批大小各写几遍
 * </ul>
 *
 * <p>前两种是闭环：users 个虚拟用户不停地发，一个用例压 caseSec 秒就换下一个，所有用例排成一队依次压；每个用例开头的
 * settleSec 秒请求名带「(预热)」不计数（换了一种查询，MySQL 的缓冲池要换一批数据页）。闭环下每秒完成多少次就是这种写法的吞吐。
 * 批量写是 1 个虚拟用户按顺序一个一个调，每次调用写 batchRows 行、返回服务端量的用时，结果另存一份 batch-write.csv。
 *
 * <pre>
 * 参数（-D）                  默认                    说明
 * ha.appUrl                   http://localhost:8080   mall-app 的地址
 * ha.mode                     deep-paging             deep-paging | n-plus-one | batch-write
 * ha.users                    4                       闭环的虚拟用户数（前两种模式）
 * ha.caseSec / ha.settleSec   20 / 5                  每个用例压多久；开头多少秒不计数
 * ha.hotUserId                800999999               翻页翻谁的订单（种子数据里的大客户）
 * ha.depths                   0,1000,10000,100000     翻到第几行（deep-paging）
 * ha.seedFirstUserId          800000000               种子用户的起始 ID（n-plus-one）
 * ha.seedUsers                100                     在前多少个种子用户里随机挑（n-plus-one）
 * ha.batchCases               见代码                  批量写的用例，「写法:批大小」逗号分隔
 * ha.batchRows                20000                   每次调用写多少行
 * ha.batchRepeat              3                       每个用例写几遍（另有一遍预热，行数是十分之一）
 * ha.timeoutMs                10000                   客户端超时（批量写固定 300 秒）
 * </pre>
 */
public class DataAccessSimulation extends Simulation {

    private static final Logger log = LoggerFactory.getLogger(DataAccessSimulation.class);

    static final String WARMUP_SUFFIX = " (预热)";

    private final HaConfig cfg = new HaConfig("j-data-access");
    private final Timeline timeline = new Timeline();

    private final String appUrl = cfg.str("ha.appUrl", "http://localhost:8080");
    private final String mode = cfg.str("ha.mode", "deep-paging");
    private final int users = cfg.integer("ha.users", 4);
    private final int caseSec = cfg.integer("ha.caseSec", 20);
    private final int settleSec = cfg.integer("ha.settleSec", 5);
    private final long hotUserId = cfg.longValue("ha.hotUserId", 800_999_999L);
    private final List<String> depths = cfg.list("ha.depths", "0,1000,10000,100000");
    private final long seedFirstUserId = cfg.longValue("ha.seedFirstUserId", 800_000_000L);
    private final int seedUsers = cfg.integer("ha.seedUsers", 100);
    private final List<String> batchCases = cfg.list(
            "ha.batchCases",
            "single:1,single-tx:1000,multi-values:10,multi-values:100,multi-values:1000,multi-values:5000,"
                    + "jdbc-batch:10,jdbc-batch:100,jdbc-batch:1000,jdbc-batch:5000");
    private final int batchRows = cfg.integer("ha.batchRows", 20000);
    private final int batchRepeat = cfg.integer("ha.batchRepeat", 3);
    private final Duration timeout = Duration.ofMillis(cfg.integer("ha.timeoutMs", 10000));

    /** 游标分页翻到各个深度时带的游标：{@link #before} 里向 mall-app 问来的。 */
    private final Map<String, String> cursorByDepth = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<String> batchResults = new ConcurrentLinkedQueue<>();
    private volatile long startMillis = Long.MAX_VALUE;

    /** 闭环模式的一个用例：名字（报告和 timeline.csv 里的请求名）与请求地址。 */
    private record Case(String name, Function<Session, String> url) {}

    {
        log.info("实验 J 参数：{}", cfg.used());
        switch (mode) {
            case "deep-paging" -> closedLoop(pagingCases());
            case "n-plus-one" -> closedLoop(listCases());
            case "batch-write" -> setUp(batchWrite().injectOpen(atOnceUsers(1))).protocols(HaChains.protocol(appUrl));
            default -> throw new IllegalArgumentException("ha.mode 只能是 deep-paging / n-plus-one / batch-write：" + mode);
        }
    }

    // ---------------------------------------------------------------- 闭环：用例排队依次压

    private List<Case> pagingCases() {
        List<Case> cases = new ArrayList<>();
        for (String depth : depths) {
            for (String m : List.of("offset", "deferred", "cursor")) {
                String label = switch (m) {
                    case "offset" -> "偏移量";
                    case "deferred" -> "延迟关联";
                    default -> "游标";
                };
                cases.add(new Case(
                        "翻到第 " + depth + " 行：" + label,
                        s -> "/internal/demo/perf/orders/page?mode=" + m + "&userId=" + hotUserId + "&size=20&offset="
                                + depth
                                + ("cursor".equals(m)
                                                && !cursorByDepth
                                                        .getOrDefault(depth, "")
                                                        .isEmpty()
                                        ? "&cursorId=" + cursorByDepth.get(depth)
                                        : "")));
            }
        }
        return cases;
    }

    private List<Case> listCases() {
        List<Case> cases = new ArrayList<>();
        for (String m : List.of("n-plus-one", "in", "join")) {
            String label = switch (m) {
                case "n-plus-one" -> "逐单查明细";
                case "in" -> "一次 IN";
                default -> "一次 JOIN";
            };
            cases.add(new Case(
                    "最近 20 单带明细：" + label,
                    s -> "/internal/demo/perf/orders/with-items?mode=" + m + "&size=20&userId="
                            + (seedFirstUserId + ThreadLocalRandom.current().nextInt(seedUsers))));
        }
        return cases;
    }

    private void closedLoop(List<Case> cases) {
        List<Choice.WithKey> choices = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            choices.add(onCase(i * 2).then(get(c.name() + WARMUP_SUFFIX, c.url())));
            choices.add(onCase(i * 2 + 1).then(get(c.name(), c.url())));
        }
        ScenarioBuilder loop = scenario("数据访问（闭环）")
                .asLongAs(s -> phase() < cases.size() * 2)
                .on(doSwitch(s -> phase()).on(choices));
        ChainBuilder marks = exec(s -> s);
        for (Case c : cases) {
            marks = marks.exec(mark(c.name() + "（" + users + " 个虚拟用户，" + caseSec + " 秒，前 " + settleSec + " 秒不计）"))
                    .exec(pause(Duration.ofSeconds(caseSec)));
        }
        Duration total = Duration.ofSeconds((long) cases.size() * caseSec);
        setUp(
                        loop.injectClosed(constantConcurrentUsers(users).during(total)),
                        scenario("实验控制").exec(marks).exec(mark("结束")).injectOpen(atOnceUsers(1)))
                .protocols(HaChains.protocol(appUrl))
                .maxDuration(total.plusSeconds(15));
    }

    /** 现在轮到第几个用例的哪一段：用例序号 × 2，过了开头的 settleSec 秒再加 1。 */
    private int phase() {
        long elapsed = Math.max(0, System.currentTimeMillis() - startMillis);
        int idx = (int) (elapsed / (caseSec * 1000L));
        boolean settled = elapsed % (caseSec * 1000L) >= settleSec * 1000L;
        return idx * 2 + (settled ? 1 : 0);
    }

    private ChainBuilder get(String name, Function<Session, String> url) {
        return exec(HaChains.call(name, n -> http(n).get(url), List.of(200), timeout, timeline));
    }

    // ---------------------------------------------------------------- 批量写：一个一个调

    private ScenarioBuilder batchWrite() {
        ChainBuilder all = exec(mark("预热：每个用例写 " + batchRows / 10 + " 行"));
        for (String c : batchCases) {
            all = all.exec(batchCall(c, batchRows / 10, true));
        }
        for (int r = 1; r <= batchRepeat; r++) {
            all = all.exec(mark("第 " + r + " 遍：每个用例写 " + batchRows + " 行"));
            for (String c : batchCases) {
                all = all.exec(batchCall(c, batchRows, false));
            }
        }
        return scenario("批量写").exec(all).exec(mark("结束"));
    }

    private ChainBuilder batchCall(String spec, int rows, boolean warmup) {
        String[] p = spec.split(":");
        String m = p[0];
        int batchSize = Integer.parseInt(p[1]);
        String name = "批量写 " + m + ("single".equals(m) ? "" : " 每批 " + batchSize) + (warmup ? WARMUP_SUFFIX : "");
        return exec(HaChains.call(
                        name,
                        n -> http(n).post("/internal/demo/perf/batch-insert?mode=" + m + "&rows=" + rows + "&batchSize="
                                        + batchSize)
                                .check(
                                        jsonPath("$.elapsedMs").optional().saveAs("bw_elapsedMs"),
                                        jsonPath("$.rowsPerSec").optional().saveAs("bw_rowsPerSec"),
                                        jsonPath("$.rewriteBatchedStatements")
                                                .optional()
                                                .saveAs("bw_rewrite")),
                        List.of(200),
                        Duration.ofSeconds(300),
                        timeline))
                .exec(s -> {
                    if (!warmup && s.contains("bw_elapsedMs")) {
                        batchResults.add(m + "," + batchSize + "," + rows + "," + s.getString("bw_rewrite") + ","
                                + s.getString("bw_elapsedMs") + "," + s.getString("bw_rowsPerSec"));
                    }
                    return s.remove("bw_elapsedMs").remove("bw_rowsPerSec").remove("bw_rewrite");
                });
    }

    private ChainBuilder mark(String event) {
        return exec(s -> {
            timeline.mark(event);
            return s;
        });
    }

    // ---------------------------------------------------------------- 开始与结束

    @Override
    public void before() {
        if ("deep-paging".equals(mode)) {
            HttpClient client = HttpClient.newHttpClient();
            Pattern id = Pattern.compile("\"cursorId\"\\s*:\\s*\"(\\d*)\"");
            for (String depth : depths) {
                try {
                    HttpResponse<String> r = client.send(
                            HttpRequest.newBuilder(URI.create(appUrl + "/internal/demo/perf/orders/cursor-at?userId="
                                            + hotUserId + "&offset=" + depth))
                                    .timeout(Duration.ofSeconds(30))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    Matcher m = id.matcher(r.body());
                    if (r.statusCode() != 200 || !m.find()) {
                        throw new IllegalStateException("问不到第 " + depth + " 行的游标：" + r.statusCode() + " " + r.body());
                    }
                    cursorByDepth.put(depth, m.group(1));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            log.info("各深度的游标：{}", cursorByDepth);
        }
        startMillis = System.currentTimeMillis();
        cfg.markStarted();
    }

    @Override
    public void after() {
        try {
            timeline.write(cfg.outDir(), cfg.usedAsCsvLines());
            if ("batch-write".equals(mode)) {
                List<String> lines = new ArrayList<>();
                lines.add("mode,batch_size,rows,rewrite_batched_statements,elapsed_ms,rows_per_sec");
                lines.addAll(batchResults);
                Files.write(cfg.outDir().resolve("batch-write.csv"), lines, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
