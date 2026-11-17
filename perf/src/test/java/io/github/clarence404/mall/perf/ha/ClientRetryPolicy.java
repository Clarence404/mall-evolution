package io.github.clarence404.mall.perf.ha;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟 App 的重试策略（重试风暴实验的自变量）。三种模式：
 *
 * <ul>
 *   <li>NONE：失败就失败，不重试
 *   <li>IMMEDIATE：失败立刻重试，最多 maxRetries 次，不等待、不看 Retry-After、不设预算——「不受控的重试」
 *   <li>BACKOFF：指数退避 + 全抖动（等待时间在 [0, min(cap, base × 2^n)] 里随机取），响应带 Retry-After 时至少等它说的秒数，
 *       并受 {@link RetryBudget} 约束
 * </ul>
 *
 * <p>只对「可能是暂时性」的失败重试：没拿到响应（超时、连接被拒，状态记为 0）、429、5xx；其余 4xx 是明确的业务结果，重试也没用。
 */
public record ClientRetryPolicy(
        Mode mode, int maxRetries, Duration base, Duration cap, boolean honorRetryAfter, RetryBudget budget) {

    public enum Mode {
        NONE,
        IMMEDIATE,
        BACKOFF
    }

    /** 从系统属性读：ha.retry.mode / max / baseMs / capMs / honorRetryAfter / budget；不传 mode 时不重试。 */
    public static ClientRetryPolicy from(HaConfig cfg) {
        return from(cfg, "none");
    }

    /** 同上，mode 没传时用 defaultMode。 */
    public static ClientRetryPolicy from(HaConfig cfg, String defaultMode) {
        Mode mode = Mode.valueOf(cfg.str("ha.retry.mode", defaultMode).trim().toUpperCase(Locale.ROOT));
        int max = cfg.integer("ha.retry.max", 3);
        Duration base = Duration.ofMillis(cfg.integer("ha.retry.baseMs", 200));
        Duration cap = Duration.ofMillis(cfg.integer("ha.retry.capMs", 2000));
        boolean honor = cfg.bool("ha.retry.honorRetryAfter", true);
        double ratio = cfg.dbl("ha.retry.budget", 0.1);
        RetryBudget budget = mode == Mode.BACKOFF && ratio > 0 ? new RetryBudget(ratio, 10) : RetryBudget.unlimited();
        return new ClientRetryPolicy(mode, max, base, cap, honor, budget);
    }

    static boolean retriable(int status) {
        return status == 0 || status == 429 || status >= 500;
    }

    /**
     * 第 attempt 次尝试（从 0 起）得到 status 之后，是否再试一次。会消耗重试预算，所以每次失败只能调用一次。
     */
    public boolean shouldRetry(int status, int attempt) {
        if (mode == Mode.NONE || attempt >= maxRetries || !retriable(status)) {
            return false;
        }
        if (mode == Mode.IMMEDIATE) {
            return true; // 不受控：只要还有次数就立刻再试，连 429 也照样重试
        }
        return budget.tryRetry();
    }

    /** 第 attempt 次失败后等多久再试。retryAfter 是响应头的原文（秒数），没有就是 null。 */
    public Duration backoff(int attempt, String retryAfter) {
        if (mode != Mode.BACKOFF) {
            return Duration.ZERO;
        }
        long ceiling = Math.min(cap.toMillis(), base.toMillis() << Math.min(attempt, 20));
        long wait = ThreadLocalRandom.current().nextLong(ceiling + 1); // 全抖动
        if (honorRetryAfter && retryAfter != null) {
            try {
                wait = Math.max(wait, Long.parseLong(retryAfter.trim()) * 1000);
            } catch (NumberFormatException ignored) {
                // Retry-After 也可能是 HTTP 日期格式，服务端只用秒数，这里忽略其他格式
            }
        }
        return Duration.ofMillis(wait);
    }
}
