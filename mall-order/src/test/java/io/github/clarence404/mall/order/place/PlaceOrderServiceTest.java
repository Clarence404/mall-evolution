package io.github.clarence404.mall.order.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.remote.RemoteBizException;
import io.github.clarence404.mall.inventory.api.ReleaseStockRequest;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.api.ReserveStockRequest;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** 预占 → 写单；结果未知按失败处理并释放，明确失败什么都不做，熔断或舱壁拒绝返回 SYSTEM_BUSY 且不释放。 */
class PlaceOrderServiceTest {

    static final String ORDER_NO = "18492033857208325120001";

    OrderMapper orderMapper = mock(OrderMapper.class);
    PricingService pricingService = mock(PricingService.class);
    PlaceOrderTxService txService = mock(PlaceOrderTxService.class);
    OrderNoGenerator orderNoGenerator = mock(OrderNoGenerator.class);
    InventoryReserveClient inventoryApi = mock(InventoryReserveClient.class);
    PendingOrderLimiter pendingOrderLimiter = mock(PendingOrderLimiter.class);
    PlaceOrderService service = new PlaceOrderService(
            orderMapper,
            pricingService,
            txService,
            orderNoGenerator,
            inventoryApi,
            pendingOrderLimiter,
            new PromoProperties());

    List<ReserveLine> lines = List.of(new ReserveLine(20002L, 1), new ReserveLine(20001L, 2));

    @BeforeEach
    void setUp() {
        when(pricingService.price(any())).thenReturn(Fixtures.pricedItems());
        when(orderNoGenerator.next(10001L)).thenReturn(ORDER_NO);
    }

    @Test
    void sameKeyReplaysWithoutReserving() {
        when(orderMapper.selectByUserAndBizNo(10001L, "key-0000000000001")).thenReturn(new Order());

        assertThat(service.place(10001L, "key-0000000000001", Fixtures.request())
                        .replayed())
                .isTrue();
        verifyNoInteractions(inventoryApi, txService);
    }

    @Test
    void reservesWithOrderNoAsIdempotencyKeyThenWritesOrder() {
        when(txService.create(10001L, ORDER_NO, "key-0000000000001", Fixtures.request(), Fixtures.pricedItems()))
                .thenReturn(new Order());

        service.place(10001L, "key-0000000000001", Fixtures.request());

        verify(inventoryApi).reserve(new ReserveStockRequest(ORDER_NO, lines, 15));
        verify(inventoryApi, never()).release(any());
    }

    @Test
    void definiteFailureFromInventoryReleasesNothing() {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setProperty("code", "STOCK_INSUFFICIENT");
        doThrow(new RemoteBizException(HttpStatus.CONFLICT, pd))
                .when(inventoryApi)
                .reserve(any());

        assertThatThrownBy(() -> service.place(10001L, "key-0000000000001", Fixtures.request()))
                .isInstanceOf(RemoteBizException.class);
        verify(inventoryApi, never()).release(any());
        verifyNoInteractions(txService);
    }

    @Test
    void circuitOpenOrBulkheadFullIsSystemBusyAndNothingToRelease() {
        doThrow(io.github.resilience4j.circuitbreaker.CallNotPermittedException.createCallNotPermittedException(
                        io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("inventory-reserve")))
                .doThrow(io.github.resilience4j.bulkhead.BulkheadFullException.createBulkheadFullException(
                        io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("inventory-reserve")))
                .when(inventoryApi)
                .reserve(any());

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.place(10001L, "key-0000000000001", Fixtures.request()))
                    .isInstanceOf(io.github.clarence404.mall.common.error.BizException.class)
                    .extracting(e -> ((io.github.clarence404.mall.common.error.BizException) e).getErrorCode())
                    .isEqualTo(io.github.clarence404.mall.common.error.CommonErrorCode.SYSTEM_BUSY);
        }
        verify(inventoryApi, never()).release(any()); // 请求没发出去，库存没动
    }

    @Test
    void activitySkuGetsFiveMinutePayTimeout() {
        PromoProperties promo = new PromoProperties();
        promo.getActivitySkuIds().add(20001L);
        PlaceOrderService promoService = new PlaceOrderService(
                orderMapper, pricingService, txService, orderNoGenerator, inventoryApi, pendingOrderLimiter, promo);

        promoService.place(10001L, "key-0000000000001", Fixtures.request());

        verify(inventoryApi).reserve(new ReserveStockRequest(ORDER_NO, lines, 5));
    }

    @Test
    void pendingLimitIsCheckedBeforeReserving() {
        doThrow(new io.github.clarence404.mall.common.error.BizException(
                        io.github.clarence404.mall.order.api.OrderErrorCode.ORDER_PENDING_LIMIT))
                .when(pendingOrderLimiter)
                .check(org.mockito.ArgumentMatchers.eq(10001L), any());

        assertThatThrownBy(() -> service.place(10001L, "key-0000000000001", Fixtures.request()))
                .isInstanceOf(io.github.clarence404.mall.common.error.BizException.class);
        verifyNoInteractions(inventoryApi);
    }

    @Test
    void unknownResultReleasesImmediatelyWithoutRetry() {
        doThrow(new ResourceAccessException("read timeout")).when(inventoryApi).reserve(any());

        assertThatThrownBy(() -> service.place(10001L, "key-0000000000001", Fixtures.request()))
                .isInstanceOf(ResourceAccessException.class);
        verify(inventoryApi).reserve(any()); // 只调一次，不重试
        verify(inventoryApi).release(new ReleaseStockRequest(ORDER_NO, lines));
    }

    @Test
    void serverErrorFromInventoryIsUnknownAndReleasesImmediately() {
        // 5xx 不是明确失败：库存服务可能已经预占成功、返回时才出错
        doThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null))
                .when(inventoryApi)
                .reserve(any());

        assertThatThrownBy(() -> service.place(10001L, "key-0000000000001", Fixtures.request()))
                .isInstanceOf(HttpServerErrorException.class);
        verify(inventoryApi).release(new ReleaseStockRequest(ORDER_NO, lines));
        verifyNoInteractions(txService);
    }

    @Test
    void rejectedReservationIsDefiniteFailureWithoutWritingOrder() {
        when(inventoryApi.reserve(any())).thenReturn(ReserveResult.REJECTED);

        assertThatThrownBy(() -> service.place(10001L, "key-0000000000001", Fixtures.request()))
                .isInstanceOf(BizException.class);
        verify(inventoryApi, never()).release(any()); // 被空回滚占位挡住，本来就没预占上
        verifyNoInteractions(txService);
    }

    @Test
    void duplicateKeyReleasesThisReservationAndReplaysWinner() {
        Order winner = new Order();
        when(orderMapper.selectByUserAndBizNo(10001L, "key-0000000000001")).thenReturn(null, winner);
        when(txService.create(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_user_biz"));

        PlaceOrderResult r = service.place(10001L, "key-0000000000001", Fixtures.request());

        assertThat(r.replayed()).isTrue();
        assertThat(r.order()).isSameAs(winner);
        verify(inventoryApi).release(new ReleaseStockRequest(ORDER_NO, lines));
    }

    @Test
    void writeFailureReleasesAndReleaseFailureIsSwallowed() {
        when(txService.create(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        doThrow(new ResourceAccessException("inventory down"))
                .when(inventoryApi)
                .release(any());

        assertThatThrownBy(() -> service.place(10001L, "key-0000000000001", Fixtures.request()))
                .isInstanceOf(DataAccessResourceFailureException.class); // 原始异常，不被释放失败覆盖
    }
}
