package io.github.clarence404.mall.order.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.InventoryErrorCode;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PlaceOrderTxServiceTest {

    InventoryApi inventoryApi = mock(InventoryApi.class);
    OrderMapper orderMapper = mock(OrderMapper.class);
    OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    PlaceOrderTxService service = new PlaceOrderTxService(inventoryApi, orderMapper, orderItemMapper);

    @Test
    void deductsInSkuAscendingOrderThenWritesOrder() {
        when(inventoryApi.deduct(anyLong(), anyInt())).thenReturn(true);

        Order order = service.create(10001L, "key-0000000000001", Fixtures.request(), Fixtures.pricedItems());

        InOrder inOrder = inOrder(inventoryApi, orderMapper, orderItemMapper);
        inOrder.verify(inventoryApi).deduct(20001L, 2);
        inOrder.verify(inventoryApi).deduct(20002L, 1);
        inOrder.verify(orderMapper).insert(order);
        inOrder.verify(orderItemMapper).batchInsert(order.items());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getOrderNo()).isEqualTo(String.valueOf(order.getId())).hasSize(19);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(new BigDecimal("92.60"));
        assertThat(order.getPayAmount()).isEqualByComparingTo(new BigDecimal("92.60"));
        assertThat(order.items())
                .hasSize(2)
                .allSatisfy(i -> assertThat(i.getOrderId()).isEqualTo(order.getId()));
    }

    @Test
    void insufficientStockThrowsAndWritesNothing() {
        when(inventoryApi.deduct(20001L, 2)).thenReturn(false);

        assertThatThrownBy(
                        () -> service.create(10001L, "key-0000000000001", Fixtures.request(), Fixtures.pricedItems()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(InventoryErrorCode.STOCK_INSUFFICIENT);
        verify(orderMapper, never()).insert(any());
    }
}
