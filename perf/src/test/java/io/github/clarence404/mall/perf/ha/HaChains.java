package io.github.clarence404.mall.perf.ha;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.http.HttpDsl.header;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * 几个实验共用的 Gatling 片段：HTTP 协议、带计时与重试的请求、故障注入、下单请求体。
 *
 * <p>所有业务请求都经 {@link #call}：发请求 → 把状态码与耗时记进 {@link Timeline} → 按 {@link ClientRetryPolicy} 决定是否重试。
 * 重试的请求在报告里名字后面带「(重试)」，和首次请求分开统计，放大倍数 = 两者之和 / 首次请求数。
 */
public final class HaChains {

    /** Gatling 报告里重试请求的名字后缀。 */
    public static final String RETRY_SUFFIX = " (重试)";

    private HaChains() {}

    /**
     * 所有虚拟用户共用一个连接池（shareConnections）：开放模型下每秒新来几十上百个虚拟用户，各自建连接会在 Windows 上很快把临时端口
     * 用光（TIME_WAIT 120 秒）；共用连接池也更像「网关 / App 后端」这类长连接调用方。
     */
    public static HttpProtocolBuilder protocol(String baseUrl) {
        return http.baseUrl(baseUrl)
                .shareConnections()
                .disableWarmUp()
                .acceptHeader("application/json")
                .contentTypeHeader("application/json");
    }

    /**
     * 发一个业务请求，带计时、按秒记录和客户端重试。
     *
     * @param name 请求名（报告与 timeline.csv 里的 request 列）
     * @param build 用传入的请求名构建请求（方法、路径、请求头、请求体，可以再加自己的 check）
     * @param okStatuses 算作成功的状态码，其余记为 KO
     * @param timeout 客户端超时：超过就当作没拿到响应（状态 0）
     */
    public static ChainBuilder call(
            String name,
            Function<Function<Session, String>, HttpRequestActionBuilder> build,
            List<Integer> okStatuses,
            Duration timeout,
            ClientRetryPolicy policy,
            Timeline timeline) {
        return call(name, build, okStatuses, timeout, policy, timeline, Function.identity());
    }

    /**
     * 同上，另外在每次重试之前先用 beforeRetry 改一下 session——幂等实验用它决定重试时换不换 Idempotency-Key。
     */
    public static ChainBuilder call(
            String name,
            Function<Function<Session, String>, HttpRequestActionBuilder> build,
            List<Integer> okStatuses,
            Duration timeout,
            ClientRetryPolicy policy,
            Timeline timeline,
            Function<Session, Session> beforeRetry) {
        HttpRequestActionBuilder request = build.apply(s -> s.getInt("ha_attempt") == 0 ? name : name + RETRY_SUFFIX)
                .requestTimeout(timeout)
                .check(
                        status().saveAs("ha_status"), // 只为记下状态码：存在即通过
                        status().in(okStatuses), // 决定报告里的 OK / KO
                        header("Retry-After").optional().saveAs("ha_retryAfter"));
        ChainBuilder attempt = exec(s -> (s.getInt("ha_attempt") > 0 ? beforeRetry.apply(s) : s)
                        .remove("ha_status")
                        .remove("ha_retryAfter")
                        .set("ha_t0", System.nanoTime()))
                .exec(request)
                .exec(s -> afterAttempt(s, name, policy, timeline))
                .doIf(s -> s.getBoolean("ha_again"))
                .then(pause(s -> Duration.ofMillis(s.getLong("ha_backoffMs"))));
        return exec(s -> s.set("ha_attempt", 0).set("ha_again", true))
                .asLongAs(s -> s.getBoolean("ha_again"))
                .on(attempt);
    }

    private static Session afterAttempt(Session s, String name, ClientRetryPolicy policy, Timeline timeline) {
        int attempt = s.getInt("ha_attempt");
        int status = s.contains("ha_status") ? s.getInt("ha_status") : 0;
        long elapsedMicros = (System.nanoTime() - s.getLong("ha_t0")) / 1_000;
        timeline.recordMicros(attempt == 0 ? name : name + RETRY_SUFFIX, status, elapsedMicros);
        if (attempt == 0) {
            policy.budget().onFirstAttempt();
        }
        boolean again = policy.shouldRetry(status, attempt);
        long backoff = again
                ? policy.backoff(attempt, s.contains("ha_retryAfter") ? s.getString("ha_retryAfter") : null)
                        .toMillis()
                : 0;
        return s.set("ha_attempt", attempt + 1)
                .set("ha_again", again)
                .set("ha_lastStatus", status)
                .set("ha_backoffMs", backoff);
    }

    /** 不重试的版本。 */
    public static ChainBuilder call(
            String name,
            Function<Function<Session, String>, HttpRequestActionBuilder> build,
            List<Integer> okStatuses,
            Duration timeout,
            Timeline timeline) {
        return call(
                name,
                build,
                okStatuses,
                timeout,
                new ClientRetryPolicy(
                        ClientRetryPolicy.Mode.NONE, 0, Duration.ZERO, Duration.ZERO, false, RetryBudget.unlimited()),
                timeline);
    }

    /**
     * 注入或更新一条故障规则（{@code PUT <serviceUrl>/internal/demo/faults/<rule>}），并在 events.csv 记一笔。
     * serviceUrl 是服务的业务端口地址（如 http://localhost:8082），故障接口不经网关。
     */
    public static ChainBuilder putFault(
            String serviceUrl, String rule, String specJson, String event, Timeline timeline) {
        return exec(http("故障：" + event)
                        .put(serviceUrl + "/internal/demo/faults/" + rule)
                        .body(StringBody(specJson))
                        .asJson())
                .exec(s -> {
                    timeline.mark(event + " " + specJson);
                    return s;
                });
    }

    /** 撤销一条故障规则。 */
    public static ChainBuilder deleteFault(String serviceUrl, String rule, String event, Timeline timeline) {
        return exec(http("故障：" + event).delete(serviceUrl + "/internal/demo/faults/" + rule))
                .exec(s -> {
                    timeline.mark(event);
                    return s;
                });
    }

    /** 下单请求体：一件商品，SKU 从 session 的 skuId 取。 */
    public static String placeOrderBody(Session s) {
        return "{\"lines\":[{\"skuId\":\"" + s.getString("skuId") + "\",\"quantity\":1}],"
                + "\"address\":{\"receiver\":\"实验\",\"phone\":\"13800000000\",\"detail\":\"高可用实验\"}}";
    }

    /** 在给定的 SKU 里随机挑一个写进 session。 */
    public static Session pickSku(Session s, List<String> skus) {
        return s.set("skuId", skus.get(ThreadLocalRandom.current().nextInt(skus.size())));
    }

    /** 每次请求一个新的幂等键。 */
    public static Session newIdempotencyKey(Session s) {
        return s.set("idemKey", UUID.randomUUID().toString());
    }
}
