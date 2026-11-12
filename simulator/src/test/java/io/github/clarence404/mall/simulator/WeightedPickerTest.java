package io.github.clarence404.mall.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.mall.simulator.SimulatorProperties.Behavior;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WeightedPickerTest {

    @Test
    void frequenciesFollowWeights() {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("browse", 60.0);
        w.put("orders", 30.0);
        w.put("buy", 10.0);
        WeightedPicker<String> picker = new WeightedPicker<>(w);
        Random random = new Random(42);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 100_000; i++) {
            counts.merge(picker.pick(random), 1, Integer::sum);
        }
        assertThat(counts.get("browse") / 100_000.0).isBetween(0.59, 0.61);
        assertThat(counts.get("orders") / 100_000.0).isBetween(0.29, 0.31);
        assertThat(counts.get("buy") / 100_000.0).isBetween(0.09, 0.11);
        assertThat(picker.probability("buy")).isEqualTo(0.1);
    }

    @Test
    void zeroWeightIsNeverPicked() {
        Map<Behavior, Double> w = new EnumMap<>(Behavior.class);
        w.put(Behavior.BROWSE, 1.0);
        w.put(Behavior.PURCHASE, 0.0); // 配成 0 = 关掉下单
        WeightedPicker<Behavior> picker = new WeightedPicker<>(w);
        Random random = new Random(1);
        for (int i = 0; i < 10_000; i++) {
            assertThat(picker.pick(random)).isEqualTo(Behavior.BROWSE);
        }
        assertThat(picker.probability(Behavior.PURCHASE)).isZero();
    }

    @Test
    void needsAtLeastOnePositiveWeight() {
        assertThatThrownBy(() -> new WeightedPicker<>(Map.of("a", 0.0))).isInstanceOf(IllegalArgumentException.class);
    }
}
