package io.github.clarence404.mall.order.pay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.order.api.OrderPaidEvent;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.order.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PayCallbackServiceTest {

    PaymentMapper paymentMapper = mock(PaymentMapper.class);
    OrderMapper orderMapper = mock(OrderMapper.class);
    OutboxWriter outboxWriter = mock(OutboxWriter.class);
    PayCallbackService service = new PayCallbackService(paymentMapper, orderMapper, outboxWriter);
    PayNotify notify = new PayNotify("P1", "T1", 1L, "1001", 10001L, new BigDecimal("79.80"), LocalDateTime.now());

    @Test
    void duplicateCallbackDoesNothing() {
        when(paymentMapper.markSuccess("P1", "T1")).thenReturn(0);

        service.onPaid(notify);

        verify(orderMapper, never()).updateStatus(anyLong(), any(), any());
        verify(outboxWriter, never()).append(anyString(), anyString(), any(), any());
    }

    @Test
    void paymentAfterOrderClosedTurnsIntoRefund() {
        when(paymentMapper.markSuccess("P1", "T1")).thenReturn(1);
        when(orderMapper.updateStatus(1L, OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_SHIPMENT))
                .thenReturn(0);

        service.onPaid(notify);

        verify(paymentMapper).markRefundPending("P1");
        verify(outboxWriter, never()).append(anyString(), anyString(), any(), any());
    }

    @Test
    void paidWritesOrderPaidIntoOutboxInSameTransaction() {
        when(paymentMapper.markSuccess("P1", "T1")).thenReturn(1);
        when(orderMapper.updateStatus(1L, OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_SHIPMENT))
                .thenReturn(1);

        service.onPaid(notify);

        verify(outboxWriter).append("order-paid", "1001", OrderPaidEvent.of(notify), null);
    }
}
