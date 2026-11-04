package io.github.clarence404.mall.order.cancel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItem;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class OrderCloseServiceTest {

    OrderMapper orderMapper = mock(OrderMapper.class);
    OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    InventoryApi inventoryApi = mock(InventoryApi.class);
    OrderCloseService service = new OrderCloseService(orderMapper, orderItemMapper, inventoryApi);

    @Test
    void closesUnpaidOrderAndRestoresStockInSkuOrder() {
        when(orderMapper.selectByOrderNo("1001")).thenReturn(order());
        when(orderMapper.updateStatus(1L, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED))
                .thenReturn(1);
        when(orderItemMapper.selectByOrderId(1L)).thenReturn(List.of(item(20002L, 1), item(20001L, 2)));

        assertThat(service.closeIfUnpaid("1001")).isTrue();

        InOrder inOrder = inOrder(inventoryApi);
        inOrder.verify(inventoryApi).restore(20001L, 2);
        inOrder.verify(inventoryApi).restore(20002L, 1);
    }

    @Test
    void alreadyPaidOrderIsNotClosedAndStockUntouched() {
        when(orderMapper.selectByOrderNo("1001")).thenReturn(order());
        when(orderMapper.updateStatus(1L, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED))
                .thenReturn(0);

        assertThat(service.closeIfUnpaid("1001")).isFalse();
        verify(inventoryApi, never()).restore(anyLong(), anyInt());
    }

    private static Order order() {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo("1001");
        return o;
    }

    private static OrderItem item(long skuId, int qty) {
        OrderItem i = new OrderItem();
        i.setSkuId(skuId);
        i.setQuantity(qty);
        return i;
    }
}
