package io.github.clarence404.mall.perf.ha;

/**
 * 重试预算（客户端侧）：每发一次「首次请求」往桶里存 ratio 个令牌，每次重试取走 1 个，桶里不够就不重试。
 * ratio=0.1 的意思是「重试量最多是正常请求量的 10%」，下游整体故障时重试流量被封顶，而不是把请求放大成几倍。
 *
 * <p>思路同 Finagle 的 RetryBudget、gRPC 的 retry throttling；桶有上限（maxTokens），平时攒下的令牌不会在故障时一次性放出去太多。
 * ratio 小于等于 0 表示不设预算（无限重试额度），用于对照组。
 */
public final class RetryBudget {

    /** 令牌按千分之一计（定点数）：0.1 累加十次要正好是 1，浮点数做不到。 */
    private static final long ONE = 1000;

    private final double ratio;
    private final long deposit;
    private final long max;
    private long tokens;
    private long deposits;
    private long granted;
    private long denied;

    public RetryBudget(double ratio, double maxTokens) {
        this.ratio = ratio;
        this.deposit = Math.round(ratio * ONE);
        this.max = Math.round(maxTokens * ONE);
        this.tokens = max;
    }

    public static RetryBudget unlimited() {
        return new RetryBudget(0, 0);
    }

    public boolean limited() {
        return ratio > 0;
    }

    /** 每个首次请求调一次。 */
    public synchronized void onFirstAttempt() {
        deposits++;
        if (limited()) {
            tokens = Math.min(max, tokens + deposit);
        }
    }

    /** 想重试时调用：有额度返回 true 并扣掉一个令牌。 */
    public synchronized boolean tryRetry() {
        if (!limited()) {
            granted++;
            return true;
        }
        if (tokens >= ONE) {
            tokens -= ONE;
            granted++;
            return true;
        }
        denied++;
        return false;
    }

    /** 统计：首次请求数、放行的重试数、因预算不足放弃的重试数。 */
    public synchronized String summary() {
        return "firstAttempts=" + deposits + ", retriesGranted=" + granted + ", retriesDenied=" + denied;
    }
}
