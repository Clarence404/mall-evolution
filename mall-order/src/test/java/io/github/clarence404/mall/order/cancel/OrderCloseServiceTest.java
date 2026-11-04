package io.github.clarence404.mall.order.cancel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.RestoreStockRequest;
import io.github.clarence404.mall.inventory.api.StockLine;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItem;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OrderCloseServiceTest {

    OrderMapper orderMapper = mock(OrderMapper.class);
    OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
    InventoryApi inventoryApi = mock(InventoryApi.class);
    StockRestoreTaskMapper taskMapper = mock(StockRestoreTaskMapper.class);
    StockRestorer stockRestorer = new StockRestorer(taskMapper, inventoryApi);
    OrderCloseService service = new OrderCloseService(
            orderMapper,
            orderItemMapper,
            stockRestorer,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));

    @Test
    void closesUnpaidOrderThenRestoresStockRemotely() {
        when(orderMapper.selectByOrderNo("1001")).thenReturn(order());
        when(orderMapper.updateStatus(1L, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED))
                .thenReturn(1);
        when(orderItemMapper.selectByOrderId(1L)).thenReturn(List.of(item(20002L, 1), item(20001L, 2)));

        assertThat(service.closeIfUnpaid("1001")).isTrue();

        verify(taskMapper).insert(eq("1001"), anyString(), any()); // 任务与状态更新同一个事务写入
        verify(inventoryApi)
                .restore(new RestoreStockRequest("1001", List.of(new StockLine(20002L, 1), new StockLine(20001L, 2))));
        verify(taskMapper).delete("1001");
    }

    @Test
    void alreadyPaidOrderIsNotClosedAndStockUntouched() {
        when(orderMapper.selectByOrderNo("1001")).thenReturn(order());
        when(orderMapper.updateStatus(1L, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED))
                .thenReturn(0);

        assertThat(service.closeIfUnpaid("1001")).isFalse();
        verify(inventoryApi, never()).restore(any());
        verify(taskMapper, never()).insert(anyString(), anyString(), any());
    }

    @Test
    void restoreFailureKeepsTaskForRetryInsteadOfFailingTheClose() {
        when(orderMapper.selectByOrderNo("1001")).thenReturn(order());
        when(orderMapper.updateStatus(1L, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED))
                .thenReturn(1);
        when(orderItemMapper.selectByOrderId(1L)).thenReturn(List.of(item(20001L, 2)));
        doThrow(new IllegalStateException("timeout")).when(inventoryApi).restore(any());

        assertThat(service.closeIfUnpaid("1001")).isTrue();

        verify(taskMapper).markRetry(eq("1001"), any()); // 任务留在表里，等定时重试
        verify(taskMapper, never()).delete(anyString());
    }

    @Test
    void retryJobRestoresDueTasksAndDeletesThem() {
        when(taskMapper.selectDue(100))
                .thenReturn(List.of(new StockRestoreTaskMapper.Task("1001", "[{\"skuId\":20001,\"quantity\":2}]", 3)));

        assertThat(stockRestorer.retryDue()).isEqualTo(1);

        verify(inventoryApi).restore(new RestoreStockRequest("1001", List.of(new StockLine(20001L, 2))));
        verify(taskMapper).delete("1001");
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
