package io.github.clarence404.mall.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StockGuardTest {

    @Test
    void restocksBelowWatermark() {
        StockGuard guard = new StockGuard(200, 50);
        assertThat(guard.onStock(20001, 500)).isEqualTo(StockGuard.Action.NONE);
        assertThat(guard.onStock(20001, 199)).isEqualTo(StockGuard.Action.RESTOCK);
        assertThat(guard.allowOrder(20001)).isTrue(); // 能补货：照常买
    }

    @Test
    void unknownStockIsAllowed() {
        assertThat(new StockGuard(200, 50).allowOrder(20002)).isTrue(); // 由每小时上限兜底
    }

    @Test
    void stopsBuyingBelowFloorWhenRestockUnavailable() {
        StockGuard guard = new StockGuard(200, 50);
        guard.markRestockUnavailable();
        assertThat(guard.onStock(20001, 100)).isEqualTo(StockGuard.Action.NONE); // 补不了就不再尝试补
        assertThat(guard.allowOrder(20001)).isTrue();
        guard.onStock(20001, 50);
        assertThat(guard.allowOrder(20001)).isFalse();
        assertThat(guard.restockAvailable()).isFalse();
    }

    @Test
    void soldOutIsNeverAllowed() {
        StockGuard guard = new StockGuard(200, 50);
        guard.onStock(20003, 0);
        assertThat(guard.allowOrder(20003)).isFalse();
        assertThat(guard.snapshot()).containsEntry(20003L, 0);
    }
}
