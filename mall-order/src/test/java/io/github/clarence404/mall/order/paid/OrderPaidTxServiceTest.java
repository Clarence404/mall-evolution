package io.github.clarence404.mall.order.paid;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.mq.ConsumeRecordMapper;
import io.github.clarence404.mall.common.outbox.OutboxWriter;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class OrderPaidTxServiceTest {

    ConsumeRecordMapper records = mock(ConsumeRecordMapper.class);
    OrderMapper orderMapper = mock(OrderMapper.class);
    OutboxWriter outboxWriter = mock(OutboxWriter.class);
    OrderPaidTxService service = new OrderPaidTxService(records, orderMapper, outboxWriter);
    PaymentSucceeded msg = new PaymentSucceeded("1001", "P1", 10001L, new BigDecimal("79.80"), LocalDateTime.now());

    @Test
    void firstMessageMovesOrderToPendingShipmentAndPublishesOrderPaid() {
        when(records.insertIgnore("order-payment-consumer", "P1")).thenReturn(1);
        when(orderMapper.transit("1001", OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_SHIPMENT, msg.paidAt()))
                .thenReturn(1);

        service.markPaid(msg);

        verify(outboxWriter).append("order-paid", "1001", OrderPaidEvent.from(msg), null);
        verify(orderMapper).updatePaymentNo("1001", "P1");
    }

    @Test
    void duplicateMessageIsDroppedByConsumeRecord() {
        when(records.insertIgnore("order-payment-consumer", "P1")).thenReturn(0);

        service.markPaid(msg);

        verify(orderMapper, never()).transit(anyString(), any(), any(), any());
        verify(outboxWriter, never()).append(anyString(), anyString(), any(), any());
    }

    @Test
    void paymentAfterCancellationAsksPaymentServiceToRefund() {
        when(records.insertIgnore("order-payment-consumer", "P1")).thenReturn(1);
        when(orderMapper.selectStatus("1001")).thenReturn(OrderStatus.CANCELLED);

        service.markPaid(msg);

        verify(outboxWriter).append("order-refund-required", "P1", msg, null);
    }

    @Test
    void secondPaymentOfAlreadyPaidOrderIsRefunded() {
        PaymentSucceeded second =
                new PaymentSucceeded("1001", "P2", 10001L, new BigDecimal("79.80"), LocalDateTime.now());
        when(records.insertIgnore("order-payment-consumer", "P2")).thenReturn(1);
        when(orderMapper.selectStatus("1001")).thenReturn(OrderStatus.PENDING_SHIPMENT);
        when(orderMapper.selectPaymentNo("1001")).thenReturn("P1");

        service.markPaid(second);

        verify(outboxWriter).append("order-refund-required", "P2", second, null);
        verify(outboxWriter, never()).append(eq("order-paid"), anyString(), any(), any());
    }

    @Test
    void conditionalUpdateStillProtectsWhenConsumeRecordWasCleaned() {
        when(records.insertIgnore("order-payment-consumer", "P1")).thenReturn(1); // 30 天后记录已清理
        when(orderMapper.selectStatus("1001")).thenReturn(OrderStatus.PENDING_SHIPMENT);
        when(orderMapper.selectPaymentNo("1001")).thenReturn("P1"); // 就是入账的那一张

        service.markPaid(msg);

        verify(outboxWriter, never()).append(anyString(), anyString(), any(), any());
    }
}
