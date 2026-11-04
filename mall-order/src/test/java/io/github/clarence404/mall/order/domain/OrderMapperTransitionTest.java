package io.github.clarence404.mall.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** OrderMapper.updateStatus 先按流转表校验，再做条件更新。 */
class OrderMapperTransitionTest {

    private final OrderMapper mapper = mock(OrderMapper.class);

    @Test
    void illegalTransitionIsRejectedBeforeTouchingDatabase() {
        when(mapper.updateStatus(anyLong(), any(), any())).thenCallRealMethod();

        assertThatThrownBy(() -> mapper.updateStatus(1L, OrderStatus.SHIPPED, OrderStatus.CANCELLED))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).casStatus(anyLong(), any(), any());
    }

    @Test
    void legalTransitionRunsConditionalUpdate() {
        when(mapper.updateStatus(anyLong(), any(), any())).thenCallRealMethod();
        when(mapper.casStatus(1L, OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_SHIPMENT))
                .thenReturn(1);

        assertThat(mapper.updateStatus(1L, OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_SHIPMENT))
                .isEqualTo(1);
    }
}
