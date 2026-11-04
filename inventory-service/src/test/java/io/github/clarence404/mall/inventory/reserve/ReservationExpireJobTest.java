package io.github.clarence404.mall.inventory.reserve;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.remote.RemoteBizException;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import io.github.clarence404.mall.order.api.OrderSummaryDTO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** 预占过期回查：订单不存在或已取消就 release，已支付及以后就 confirm，仍待支付等下一轮。 */
class ReservationExpireJobTest {

    ReservationMapper mapper = mock(ReservationMapper.class);
    ReservationService service = mock(ReservationService.class);
    OrderQueryApi orderQueryApi = mock(OrderQueryApi.class);
    ReservationExpireJob job = new ReservationExpireJob(mapper, service, orderQueryApi);

    @Test
    void decidesByOrderStatus() {
        when(mapper.selectExpiredOrderNos(200)).thenReturn(List.of("A", "B", "C", "D"));
        when(orderQueryApi.getByOrderNo("A")).thenReturn(order("A", 50));
        when(orderQueryApi.getByOrderNo("B")).thenReturn(order("B", 20));
        when(orderQueryApi.getByOrderNo("C")).thenReturn(order("C", 10));
        when(orderQueryApi.getByOrderNo("D"))
                .thenThrow(new RemoteBizException(HttpStatus.NOT_FOUND, ProblemDetail.forStatus(404)));

        job.reconcileExpired();

        verify(service).releaseByOrderNo("A");
        verify(service).confirm("B");
        verify(service, never()).confirm("C");
        verify(service, never()).releaseByOrderNo("C");
        verify(service).releaseByOrderNo("D"); // 订单不存在：写单失败、释放也失败留下的预占
    }

    @Test
    void orderServiceDownMeansTryAgainNextRound() {
        when(mapper.selectExpiredOrderNos(200)).thenReturn(List.of("A"));
        when(orderQueryApi.getByOrderNo("A")).thenThrow(new IllegalStateException("timeout"));

        job.reconcileExpired();

        verify(service, never()).releaseByOrderNo(anyString());
        verify(service, never()).confirm(anyString());
    }

    private static OrderSummaryDTO order(String orderNo, int status) {
        return new OrderSummaryDTO(orderNo, 10001L, status, BigDecimal.TEN);
    }
}
