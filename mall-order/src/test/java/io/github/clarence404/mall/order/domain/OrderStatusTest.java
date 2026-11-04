package io.github.clarence404.mall.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

    @Test
    void onlyDocumentedTransitionsAreAllowed() {
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitTo(OrderStatus.PENDING_SHIPMENT))
                .isTrue();
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitTo(OrderStatus.CANCELLED))
                .isTrue();
        assertThat(OrderStatus.PENDING_SHIPMENT.canTransitTo(OrderStatus.SHIPPED))
                .isTrue();
        assertThat(OrderStatus.PENDING_SHIPMENT.canTransitTo(OrderStatus.CLOSED))
                .isTrue();
        assertThat(OrderStatus.SHIPPED.canTransitTo(OrderStatus.COMPLETED)).isTrue();

        assertThat(OrderStatus.SHIPPED.canTransitTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitTo(OrderStatus.SHIPPED))
                .isFalse();
        assertThat(OrderStatus.CANCELLED.canTransitTo(OrderStatus.PENDING_SHIPMENT))
                .isFalse();
    }

    @Test
    void codesLeaveGaps() {
        assertThat(OrderStatus.fromCode(10)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(OrderStatus.fromCode(60)).isEqualTo(OrderStatus.CLOSED);
    }
}
