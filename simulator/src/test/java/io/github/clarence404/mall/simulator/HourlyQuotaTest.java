package io.github.clarence404.mall.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HourlyQuotaTest {

    @Test
    void slidingWindowOfOneHour() {
        HourlyQuota quota = new HourlyQuota(2);
        Instant t0 = Instant.parse("2026-11-12T10:00:00Z");
        assertThat(quota.tryAcquire(t0)).isTrue();
        assertThat(quota.tryAcquire(t0.plusSeconds(600))).isTrue();
        assertThat(quota.tryAcquire(t0.plusSeconds(1200))).isFalse(); // 一小时内已满
        assertThat(quota.tryAcquire(t0.plus(Duration.ofHours(1)))).isTrue(); // 第一单滑出窗口
        assertThat(quota.tryAcquire(t0.plus(Duration.ofMinutes(61)))).isFalse();
    }

    @Test
    void zeroMeansUnlimited() {
        HourlyQuota quota = new HourlyQuota(0);
        for (int i = 0; i < 1000; i++) {
            assertThat(quota.tryAcquire(Instant.EPOCH)).isTrue();
        }
    }
}
