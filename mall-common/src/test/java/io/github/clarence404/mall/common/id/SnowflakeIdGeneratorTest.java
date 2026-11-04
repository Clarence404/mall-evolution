package io.github.clarence404.mall.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    @Test
    void idsAreUniqueIncreasingAnd19Digits() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);
        Set<Long> ids = new HashSet<>();
        long last = 0;
        for (int i = 0; i < 100_000; i++) {
            long id = generator.nextId();
            assertThat(id).isGreaterThan(last);
            last = id;
            ids.add(id);
        }
        assertThat(ids).hasSize(100_000);
        assertThat(String.valueOf(last)).hasSize(19);
    }

    @Test
    void rejectsLargeClockBackward() {
        long[] clock = {1_800_000_000_000L};
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(2) {
            @Override
            long currentTimeMillis() {
                return clock[0];
            }
        };
        generator.nextId();
        clock[0] -= 1000;
        assertThatThrownBy(generator::nextId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void minIdAtIsLowerBoundOfIdsGeneratedAfterwards() {
        long before = System.currentTimeMillis();
        long id = new SnowflakeIdGenerator(3).nextId();
        assertThat(SnowflakeIdGenerator.minIdAt(before)).isLessThanOrEqualTo(id);
    }

    @Test
    void rejectsInvalidWorkerId() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024)).isInstanceOf(IllegalArgumentException.class);
    }
}
