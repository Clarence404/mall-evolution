package io.github.clarence404.mall.perf.ha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 「平时 → 高峰 → 平时」的日程：各段的速率与起止时刻、总请求数。 */
class BurstScheduleTest {

    @Test
    void defaultScheduleHasThreePhases() {
        BurstSchedule s = new BurstSchedule(20, 60, 60, 60, 360);

        assertThat(s.phases())
                .containsExactly(
                        new BurstSchedule.Phase(20, 0, 60, false),
                        new BurstSchedule.Phase(60, 60, 60, true),
                        new BurstSchedule.Phase(20, 120, 240, false));
        assertThat(s.burstEndSec()).isEqualTo(120);
        assertThat(s.totalRequests()).isEqualTo(9_600); // 20 × 300 + 60 × 60
    }

    @Test
    void emptyPhasesAreLeftOut() {
        assertThat(new BurstSchedule(20, 60, 0, 30, 90).phases())
                .containsExactly(new BurstSchedule.Phase(60, 0, 30, true), new BurstSchedule.Phase(20, 30, 60, false));
        assertThat(new BurstSchedule(20, 60, 30, 60, 90).phases())
                .containsExactly(new BurstSchedule.Phase(20, 0, 30, false), new BurstSchedule.Phase(60, 30, 60, true));
        assertThat(new BurstSchedule(20, 60, 30, 0, 90).phases())
                .containsExactly(new BurstSchedule.Phase(20, 0, 30, false), new BurstSchedule.Phase(20, 30, 60, false));
    }

    @Test
    void fractionalRatesRoundTheTotalUp() {
        assertThat(new BurstSchedule(0.5, 1.5, 3, 3, 9).totalRequests()).isEqualTo(8); // 0.5 × 6 + 1.5 × 3 = 7.5
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> new BurstSchedule(0, 60, 60, 60, 360)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BurstSchedule(20, 60, -1, 60, 360)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BurstSchedule(20, 60, 300, 120, 360))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出了总时长");
    }
}
