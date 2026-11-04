package io.github.clarence404.mall.order.cancel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.outbox.OutboxWriter;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItem;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.payment.api.ClosePaymentResult;
import io.github.clarence404.mall.payment.api.PaymentApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OrderCloseServiceTest {

    OrderMapper orderMapper = mock(OrderMapper.class);
    OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    PaymentApi paymentApi = mock(PaymentApi.class);
    OutboxWriter outboxWriter = mock(OutboxWriter.class);
    OrderCloseService service = new OrderCloseService(
            orderMapper,
            orderItemMapper,
            paymentApi,
            outboxWriter,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));

    @Test
    void closesPaymentThenCancelsOrderAndPublishesOrderCancelled() {
        when(orderMapper.selectByOrderNo("1001")).thenReturn(order(OrderStatus.PENDING_PAYMENT));
        when(paymentApi.close("1001")).thenReturn(ClosePaymentResult.CLOSED);
        when(orderMapper.transit("1001", OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, null))
                .thenReturn(1);
        when(orderItemMapper.selectByOrderId(1L)).thenReturn(List.of(item(20001L, 2)));

        assertThat(service.closeIfUnpaid("1001")).isTrue();

        verify(outboxWriter)
                .append(
                        "order-cancelled",
                        "1001",
                        new OrderCancelledEvent("1001", List.of(new ReserveLine(20001L, 2))),
                        null);
    }

    @Test
    void userAlreadyPaidMeansDoNotCancelAndWaitForPaymentSucceeded() {
        when(orderMapper.selectByOrderNo("1001")).thenReturn(order(OrderStatus.PENDING_PAYMENT));
        when(paymentApi.close("1001")).thenReturn(ClosePaymentResult.PAID);

        assertThat(service.closeIfUnpaid("1001")).isFalse();
        verify(orderMapper, never()).transit(anyString(), any(), any(), any());
    }

    @Test
    void alreadyPaidOrderIsNotTouched() {
        when(orderMapper.selectByOrderNo("1001")).thenReturn(order(OrderStatus.PENDING_SHIPMENT));

        assertThat(service.closeIfUnpaid("1001")).isFalse();
        verify(paymentApi, never()).close(anyString());
    }

    private static Order order(OrderStatus status) {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo("1001");
        o.setStatus(status);
        return o;
    }

    private static OrderItem item(long skuId, int qty) {
        OrderItem i = new OrderItem();
        i.setSkuId(skuId);
        i.setQuantity(qty);
        return i;
    }
}
