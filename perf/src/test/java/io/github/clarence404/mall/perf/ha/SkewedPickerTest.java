package io.github.clarence404.mall.perf.ha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 偏斜挑选：hotShare 的请求给第一个，其余平均分给剩下的。 */
class SkewedPickerTest {

    @Test
    void hotShareGoesToTheFirstItemAndTheRestIsSplitEvenly() {
        SkewedPicker picker = new SkewedPicker(List.of("20001", "20002", "20003"), 0.8);
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 1000; i++) {
            counts.merge(picker.pick(i / 1000.0), 1, Integer::sum);
        }

        assertThat(counts).containsOnlyKeys("20001", "20002", "20003").containsEntry("20001", 800);
        // 其余两个各约 100：分界点上的浮点误差可能让个别请求落到相邻的一个上
        assertThat(counts.get("20002")).isBetween(98, 102);
        assertThat(counts.get("20003")).isBetween(98, 102);
        assertThat(picker.hottest()).isEqualTo("20001");
    }

    @Test
    void boundariesStayInsideTheList() {
        SkewedPicker picker = new SkewedPicker(List.of("a", "b", "c"), 0.5);

        assertThat(picker.pick(0.0)).isEqualTo("a");
        assertThat(picker.pick(0.5)).isEqualTo("b");
        assertThat(picker.pick(Math.nextDown(1.0))).isEqualTo("c");
    }

    @Test
    void singleItemAlwaysWins() {
        SkewedPicker picker = new SkewedPicker(List.of("only"), 0.1);

        assertThat(picker.pick(0.0)).isEqualTo("only");
        assertThat(picker.pick(0.99)).isEqualTo("only");
    }

    @Test
    void zeroAndFullShareAreAllowed() {
        assertThat(new SkewedPicker(List.of("a", "b"), 0).pick(0.0)).isEqualTo("b"); // 热点一点不给：全部落在其余候选上
        assertThat(new SkewedPicker(List.of("a", "b"), 1).pick(0.999)).isEqualTo("a");
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> new SkewedPicker(List.of(), 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SkewedPicker(List.of("a"), 1.2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hotShare");
    }
}
