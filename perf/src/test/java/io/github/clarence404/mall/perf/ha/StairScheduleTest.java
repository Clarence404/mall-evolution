package io.github.clarence404.mall.perf.ha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 阶梯加压的日程：每一级的速率与起始时刻、总时长、总请求数（决定要多少实验用户和库存）。 */
class StairScheduleTest {

    @Test
    void defaultStairHasEightLevelsUpToMax() {
        StairSchedule s = StairSchedule.of(50, 50, 120, 400);

        assertThat(s.levels()).hasSize(8);
        assertThat(s.levels().getFirst()).isEqualTo(new StairSchedule.Level(1, 50, 0, 120));
        assertThat(s.levels().get(1)).isEqualTo(new StairSchedule.Level(2, 100, 120, 120));
        assertThat(s.levels().getLast()).isEqualTo(new StairSchedule.Level(8, 400, 840, 120));
        assertThat(s.totalSec()).isEqualTo(960);
        assertThat(s.totalRequests()).isEqualTo(216_000); // (50 + 100 + … + 400) × 120
        assertThat(s.peakRps()).isEqualTo(400);
    }

    @Test
    void lastLevelIsCappedAtMaxWhenMaxIsOffTheStair() {
        StairSchedule s = StairSchedule.of(50, 100, 60, 400);

        assertThat(s.levels()).extracting(StairSchedule.Level::rps).containsExactly(50.0, 150.0, 250.0, 350.0, 400.0);
        assertThat(s.levels()).extracting(StairSchedule.Level::startSec).containsExactly(0, 60, 120, 180, 240);
        assertThat(s.totalSec()).isEqualTo(300);
    }

    @Test
    void maxNotAboveStartGivesASingleLevel() {
        assertThat(StairSchedule.of(100, 50, 30, 100).levels()).containsExactly(new StairSchedule.Level(1, 100, 0, 30));
        assertThat(StairSchedule.of(100, 50, 30, 80).levels())
                .containsExactly(new StairSchedule.Level(1, 100, 0, 30)); // 上限比起点还低：只压起点这一级
    }

    @Test
    void fractionalRatesRoundTotalUp() {
        StairSchedule s = StairSchedule.of(0.5, 0.5, 3, 1);

        assertThat(s.levels()).extracting(StairSchedule.Level::rps).containsExactly(0.5, 1.0);
        assertThat(s.totalRequests()).isEqualTo(5); // 1.5 + 3 = 4.5，向上取整
    }

    @Test
    void nonPositiveArgumentsAreRejected() {
        assertThatThrownBy(() -> StairSchedule.of(0, 50, 120, 400)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StairSchedule.of(50, 0, 120, 400)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StairSchedule.of(50, 50, 0, 400)).isInstanceOf(IllegalArgumentException.class);
    }
}
