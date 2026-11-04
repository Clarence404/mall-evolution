package io.github.clarence404.mall.order.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import io.github.clarence404.mall.common.outbox.OutboxWriter;
import io.github.clarence404.mall.order.cancel.OrderTimeoutMessage;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PlaceOrderTxServiceTest {

    OrderMapper orderMapper = mock(OrderMapper.class);
    OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    OutboxWriter outboxWriter = mock(OutboxWriter.class);
    PlaceOrderTxService service =
            new PlaceOrderTxService(orderMapper, orderItemMapper, outboxWriter, new PromoProperties());

    @Test
    void writesOrderItemsAndTimeoutMessageInOneTransaction() {
        String orderNo = "18492033857208325120001";

        Order order = service.create(10001L, orderNo, "key-0000000000001", Fixtures.request(), Fixtures.pricedItems());

        InOrder inOrder = inOrder(orderMapper, orderItemMapper, outboxWriter);
        inOrder.verify(orderMapper).insert(order);
        inOrder.verify(orderItemMapper).batchInsert(order.items());
        inOrder.verify(outboxWriter)
                .append(
                        "order-timeout",
                        orderNo,
                        new OrderTimeoutMessage(orderNo),
                        order.getCreatedAt().plusMinutes(15));

        assertThat(order.getOrderNo()).isEqualTo(orderNo);
        assertThat(String.valueOf(order.getId())).hasSize(19); // 主键仍是雪花 ID
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getPayAmount()).isEqualByComparingTo(new BigDecimal("92.60"));
    }
}
