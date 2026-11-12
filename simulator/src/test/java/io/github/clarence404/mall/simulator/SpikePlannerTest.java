package io.github.clarence404.mall.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SpikePlannerTest {

    @Test
    void neverEarlierThanMinGapAndAveragesAroundMean() {
        SpikePlanner planner = new SpikePlanner(Duration.ofHours(1), Duration.ofHours(6));
        Random random = new Random(3);
        Instant from = Instant.parse("2026-11-12T00:00:00Z");
        double sumHours = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            Duration gap = Duration.between(from, planner.next(from, random));
            assertThat(gap).isGreaterThanOrEqualTo(Duration.ofHours(1));
            sumHours += gap.toMillis() / 3_600_000.0;
        }
        // 指数分布均值 6 小时，再把不足 1 小时的抬到 1 小时，均值略高于 6
        assertThat(sumHours / n).isBetween(5.8, 6.6);
    }
}
