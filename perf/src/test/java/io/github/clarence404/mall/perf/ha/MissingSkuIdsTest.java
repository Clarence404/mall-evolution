package io.github.clarence404.mall.perf.ha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/** 不存在的 SKU ID：固定一批循环取，或者每次随机取；都得是正整数，且两种取法用的范围不重叠。 */
class MissingSkuIdsTest {

    @Test
    void repeatCyclesThroughAFixedSet() {
        LongSupplier ids = MissingSkuIds.repeat(MissingSkuIds.DEFAULT_BASE, 3);

        assertThat(ids.getAsLong()).isEqualTo(9_000_000_000_000L);
        assertThat(ids.getAsLong()).isEqualTo(9_000_000_000_001L);
        assertThat(ids.getAsLong()).isEqualTo(9_000_000_000_002L);
        assertThat(ids.getAsLong()).isEqualTo(9_000_000_000_000L);
    }

    @Test
    void repeatOverManyRequestsUsesExactlyCountIds() {
        LongSupplier ids = MissingSkuIds.repeat(MissingSkuIds.DEFAULT_BASE, 100);
        Set<Long> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            seen.add(ids.getAsLong());
        }

        assertThat(seen).hasSize(100).allMatch(id -> id > 0);
    }

    @Test
    void randomStaysAboveTheRepeatRange() {
        // 随机源返回 0 和上界 − 1：结果分别是范围的两端
        assertThat(MissingSkuIds.random(MissingSkuIds.DEFAULT_BASE, 100, bound -> 0)
                        .getAsLong())
                .isEqualTo(9_000_000_000_100L);
        assertThat(MissingSkuIds.random(MissingSkuIds.DEFAULT_BASE, 100, bound -> bound - 1)
                        .getAsLong())
                .isEqualTo(9_000_000_000_100L + MissingSkuIds.RANDOM_SPAN - 1);
    }

    @Test
    void randomDrawsAreEffectivelyUnique() {
        LongSupplier ids = MissingSkuIds.random(MissingSkuIds.DEFAULT_BASE, 100);
        Set<Long> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            seen.add(ids.getAsLong());
        }

        assertThat(seen).hasSizeGreaterThan(9_990).allMatch(id -> id >= 9_000_000_000_100L);
    }

    @Test
    void rejectsNonPositiveBaseAndEmptySet() {
        assertThatThrownBy(() -> MissingSkuIds.repeat(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MissingSkuIds.random(-5, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MissingSkuIds.repeat(MissingSkuIds.DEFAULT_BASE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
