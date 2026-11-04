package io.github.clarence404.mall.order.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class PlaceOrderServiceTest {

    OrderMapper orderMapper = mock(OrderMapper.class);
    PricingService pricingService = mock(PricingService.class);
    PlaceOrderTxService txService = mock(PlaceOrderTxService.class);
    PlaceOrderService service = new PlaceOrderService(orderMapper, pricingService, txService);

    @Test
    void sameKeyReplaysExistingOrderWithoutPricingOrDeducting() {
        Order existing = new Order();
        when(orderMapper.selectByUserAndBizNo(10001L, "key-0000000000001")).thenReturn(existing);

        PlaceOrderResult r = service.place(10001L, "key-0000000000001", Fixtures.request());

        assertThat(r.replayed()).isTrue();
        assertThat(r.order()).isSameAs(existing);
        verifyNoInteractions(pricingService, txService);
    }

    @Test
    void newKeyCreatesOrder() {
        Order created = new Order();
        when(pricingService.price(any())).thenReturn(Fixtures.pricedItems());
        when(txService.create(anyLong(), anyString(), any(), any())).thenReturn(created);

        PlaceOrderResult r = service.place(10001L, "key-0000000000001", Fixtures.request());

        assertThat(r.replayed()).isFalse();
        assertThat(r.order()).isSameAs(created);
    }

    @Test
    void concurrentDuplicateKeyFallsBackToReplayingWinner() {
        Order winner = new Order();
        when(orderMapper.selectByUserAndBizNo(10001L, "key-0000000000001"))
                .thenReturn(null) // 快速路径：两个并发请求都没查到
                .thenReturn(winner); // 撞唯一键后，在新的读里看到对方已提交的订单
        when(pricingService.price(any())).thenReturn(Fixtures.pricedItems());
        when(txService.create(anyLong(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_user_biz"));

        PlaceOrderResult r = service.place(10001L, "key-0000000000001", Fixtures.request());

        assertThat(r.replayed()).isTrue();
        assertThat(r.order()).isSameAs(winner);
        verify(orderMapper, never()).insert(any());
    }
}
