package io.github.clarence404.mall.payment.pay;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.order.api.MarkPaidResult;
import io.github.clarence404.mall.order.api.OrderPayApi;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/** 拆分过渡期：先更新 t_payment，再同步调用 markPaid；失败时让渠道重试回调。 */
class PayCallbackServiceTest {

    PaymentMapper paymentMapper = mock(PaymentMapper.class);
    OrderPayApi orderPayApi = mock(OrderPayApi.class);
    PayCallbackService service = new PayCallbackService(paymentMapper, orderPayApi);
    PayNotify notify = new PayNotify("P1", "T1", "1001", 10001L, new BigDecimal("79.80"), LocalDateTime.now());

    @Test
    void marksPaymentThenConfirmsOrder() {
        when(paymentMapper.markSuccess("P1", "T1")).thenReturn(1);
        when(orderPayApi.markPaid(eq("1001"), any())).thenReturn(MarkPaidResult.PAID);

        service.onPaid(notify);

        verify(orderPayApi).markPaid(eq("1001"), any());
        verify(paymentMapper, never()).markRefundPending(anyString());
    }

    @Test
    void repeatedCallbackStillConfirmsOrderBecauseLastCallMayHaveFailed() {
        when(paymentMapper.markSuccess("P1", "T1")).thenReturn(0);
        when(orderPayApi.markPaid(eq("1001"), any())).thenReturn(MarkPaidResult.ALREADY_PAID);

        service.onPaid(notify);

        verify(orderPayApi).markPaid(eq("1001"), any());
    }

    @Test
    void orderAlreadyCancelledTurnsPaymentIntoRefund() {
        when(paymentMapper.markSuccess("P1", "T1")).thenReturn(1);
        when(orderPayApi.markPaid(eq("1001"), any())).thenReturn(MarkPaidResult.ORDER_CANCELLED);

        service.onPaid(notify);

        verify(paymentMapper).markRefundPending("P1");
    }

    @Test
    void orderServiceDownFailsTheCallbackSoChannelRetries() {
        when(paymentMapper.markSuccess("P1", "T1")).thenReturn(1);
        when(orderPayApi.markPaid(eq("1001"), any())).thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.onPaid(notify)).isInstanceOf(ResourceAccessException.class);
    }
}
