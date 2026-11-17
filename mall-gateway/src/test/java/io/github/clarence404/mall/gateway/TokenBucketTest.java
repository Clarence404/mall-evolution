package io.github.clarence404.mall.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 单机令牌桶：一开始满桶允许 burst 个突发，之后按速率补充，补充不超过桶容量。 */
class TokenBucketTest {

    private final AtomicLong now = new AtomicLong(1_000);

    @Test
    void burstThenRefillAtRate() {
        TokenBucket bucket = new TokenBucket(10, 3, now::get);

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse(); // 突发用完

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100)); // 10/秒：100ms 补 1 个
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    void refillIsCappedAtBurst() {
        TokenBucket bucket = new TokenBucket(100, 2, now::get);
        bucket.tryAcquire();
        bucket.tryAcquire();

        now.addAndGet(TimeUnit.SECONDS.toNanos(60)); // 空闲很久也只攒 2 个

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    void fractionalRatesAccumulate() {
        TokenBucket bucket = new TokenBucket(0.5, 1, now::get); // 每 2 秒 1 个
        assertThat(bucket.tryAcquire()).isTrue();

        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertThat(bucket.tryAcquire()).isFalse();
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertThat(bucket.tryAcquire()).isTrue();
    }

    @Test
    void rejectsNonPositiveSettings() {
        assertThatThrownBy(() -> new TokenBucket(0, 1, now::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, 0, now::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
