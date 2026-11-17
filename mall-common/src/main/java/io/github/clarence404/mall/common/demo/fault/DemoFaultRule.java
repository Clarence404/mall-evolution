package io.github.clarence404.mall.common.demo.fault;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 仅演示环境：校验并归一化之后的故障规则（不可变）。取值越界时直接拒绝而不是悄悄截断——实验参数写错了要立刻知道。
 *
 * @param name 规则名，同名 PUT 即替换
 * @param method 大写的 HTTP 方法，null 表示任意
 * @param pathPattern 原始路径模式，回显用
 * @param pattern 解析好的路径模式
 * @param delayMs 处理前延迟
 * @param failPercent 直接失败的比例（0–100）
 * @param failStatus 失败时的状态码
 * @param maxConcurrent 并发上限，0 表示不限
 * @param responseDelayMs 处理后、响应发出前的延迟
 * @param delayPercent 两种延迟只对这个比例的请求生效（0–100）
 * @param expiresAt 自动失效的时刻
 */
public record DemoFaultRule(
        String name,
        String method,
        String pathPattern,
        PathPattern pattern,
        int delayMs,
        int failPercent,
        int failStatus,
        int maxConcurrent,
        int responseDelayMs,
        int delayPercent,
        Instant expiresAt) {

    static final int MAX_DELAY_MS = 30_000;
    static final int MAX_CONCURRENT = 1_000;
    static final int DEFAULT_TTL_SECONDS = 600;
    static final int MAX_TTL_SECONDS = 3_600;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    /** 把请求体转成规则；参数不合法抛 IllegalArgumentException，由控制器转成 400。 */
    static DemoFaultRule of(String name, DemoFaultSpec spec, Instant now) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("规则名只能是小写字母、数字和连字符，1–40 个字符");
        }
        if (spec == null || spec.path() == null || spec.path().isBlank()) {
            throw new IllegalArgumentException("path 必填，如 /internal/payments/*");
        }
        PathPattern pattern =
                PathPatternParser.defaultInstance.parse(spec.path().trim());
        int delay = range("delayMs", spec.delayMs(), 0, MAX_DELAY_MS, 0);
        int failPercent = range("failPercent", spec.failPercent(), 0, 100, 0);
        int failStatus = range("failStatus", spec.failStatus(), 400, 599, 503);
        int maxConcurrent = range("maxConcurrent", spec.maxConcurrent(), 0, MAX_CONCURRENT, 0);
        int responseDelay = range("responseDelayMs", spec.responseDelayMs(), 0, MAX_DELAY_MS, 0);
        int delayPercent = range("delayPercent", spec.delayPercent(), 0, 100, 100);
        int ttl = range("ttlSeconds", spec.ttlSeconds(), 1, MAX_TTL_SECONDS, DEFAULT_TTL_SECONDS);
        String method = spec.method() == null || spec.method().isBlank()
                ? null
                : spec.method().trim().toUpperCase(Locale.ROOT);
        return new DemoFaultRule(
                name,
                method,
                pattern.getPatternString(),
                pattern,
                delay,
                failPercent,
                failStatus,
                maxConcurrent,
                responseDelay,
                delayPercent,
                now.plus(Duration.ofSeconds(ttl)));
    }

    boolean matches(String requestMethod, String path) {
        return (method == null || method.equalsIgnoreCase(requestMethod))
                && pattern.matches(PathContainer.parsePath(path));
    }

    boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    private static int range(String field, Integer value, int min, int max, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " 取值范围 " + min + "–" + max + "，实际 " + value);
        }
        return value;
    }
}
