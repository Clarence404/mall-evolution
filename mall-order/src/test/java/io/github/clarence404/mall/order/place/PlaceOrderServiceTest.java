package io.github.clarence404.mall.order.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.inventory.api.DeductStockRequest;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.RestoreStockRequest;
import io.github.clarence404.mall.inventory.api.StockLine;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

/** 拆分过渡期的下单：先远程扣库存，写单失败再归还。 */
class PlaceOrderServiceTest {

    OrderMapper orderMapper = mock(OrderMapper.class);
    PricingService pricingService = mock(PricingService.class);
    PlaceOrderTxService txService = mock(PlaceOrderTxService.class);
    OrderNoGenerator orderNoGenerator = mock(OrderNoGenerator.class);
    InventoryApi inventoryApi = mock(InventoryApi.class);
    PlaceOrderService service =
            new PlaceOrderService(orderMapper, pricingService, txService, orderNoGenerator, inventoryApi);

    List<StockLine> lines = List.of(new StockLine(20002L, 1), new StockLine(20001L, 2));

    @BeforeEach
    void setUp() {
        when(pricingService.price(any())).thenReturn(Fixtures.pricedItems());
        when(orderNoGenerator.next(10001L)).thenReturn("18492033857208325120001");
    }

    @Test
    void sameKeyReplaysWithoutCallingInventoryAgain() {
        Order existing = new Order();
        when(orderMapper.selectByUserAndBizNo(10001L, "key-0000000000001")).thenReturn(existing);

        PlaceOrderResult r = service.place(10001L, "key-0000000000001", Fixtures.request());

        assertThat(r.replayed()).isTrue();
        assertThat(r.order()).isSameAs(existing);
        verifyNoInteractions(inventoryApi, txService);
    }

    @Test
    void deductsRemotelyThenWritesOrderLocally() {
        Order created = new Order();
        when(txService.create(eq(10001L), eq("18492033857208325120001"), anyString(), any(), any()))
                .thenReturn(created);

        PlaceOrderResult r = service.place(10001L, "key-0000000000001", Fixtures.request());

        assertThat(r.replayed()).isFalse();
        InOrder inOrder = inOrder(inventoryApi, txService);
        inOrder.verify(inventoryApi).deduct(new DeductStockRequest("18492033857208325120001", lines));
        inOrder.verify(txService).create(anyLong(), anyString(), anyString(), any(), any());
        verify(inventoryApi, never()).restore(any());
    }

    @Test
    void concurrentDuplicateRestoresStockAndReplaysWinner() {
        Order winner = new Order();
        when(orderMapper.selectByUserAndBizNo(10001L, "key-0000000000001")).thenReturn(null, winner);
        when(txService.create(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_user_biz"));

        PlaceOrderResult r = service.place(10001L, "key-0000000000001", Fixtures.request());

        assertThat(r.replayed()).isTrue();
        assertThat(r.order()).isSameAs(winner);
        verify(inventoryApi).restore(new RestoreStockRequest("18492033857208325120001", lines));
    }

    @Test
    void writeFailureRestoresStockAndRethrows() {
        when(txService.create(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.place(10001L, "key-0000000000001", Fixtures.request()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(inventoryApi).restore(new RestoreStockRequest("18492033857208325120001", lines));
    }
}
