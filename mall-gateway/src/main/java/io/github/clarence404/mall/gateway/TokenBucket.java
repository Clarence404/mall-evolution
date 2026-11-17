package io.github.clarence404.mall.gateway;

import java.util.function.LongSupplier;

/**
 * 单机令牌桶：每秒补 permitsPerSecond 个令牌，最多攒 burst 个。只在网关本机生效，几个网关实例各算各的
 * （总阈值 = 单机阈值 × 实例数，扩缩容时要跟着调），换来的是不依赖 Redis、Redis 挂了也照样限得住。
 *
 * <p>按纳秒时钟懒补充：每次取令牌时按距上次补充经过的时间补上，不需要定时线程。
 */
final class TokenBucket {

    private final double permitsPerSecond;
    private final double capacity;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(double permitsPerSecond, int burst, LongSupplier nanoClock) {
        if (permitsPerSecond <= 0 || burst <= 0) {
            throw new IllegalArgumentException("permitsPerSecond 与 burst 都要大于 0");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.capacity = burst;
        this.nanoClock = nanoClock;
        this.tokens = burst; // 一开始是满的：刚启动时允许一个突发
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    /** 取一个令牌，取到返回 true。 */
    synchronized boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * permitsPerSecond / 1_000_000_000d);
            lastRefillNanos = now;
        }
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
