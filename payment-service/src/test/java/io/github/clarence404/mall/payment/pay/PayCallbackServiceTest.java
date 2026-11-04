package io.github.clarence404.mall.payment.pay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 支付成功：t_payment 置成功与 payment-succeeded 写入同一个本地事务，不再同步调用订单服务。 */
class PayCallbackServiceTest {

    PaymentMapper paymentMapper = mock(PaymentMapper.class);
    OutboxWriter outboxWriter = mock(OutboxWriter.class);
    PayCallbackService service = new PayCallbackService(paymentMapper, outboxWriter);
    PayNotify notify = new PayNotify("P1", "T1", "1001", 10001L, new BigDecimal("79.80"), LocalDateTime.now());

    @Test
    void successWritesPaymentSucceededToOwnOutbox() {
        when(paymentMapper.markSuccess("P1", "T1")).thenReturn(1);

        service.onPaid(notify);

        verify(outboxWriter)
                .append(
                        "payment-succeeded",
                        "P1", // 消息键是支付单号：同一订单的第二张支付单也能写进 outbox
                        new PaymentSucceeded("1001", "P1", 10001L, notify.amount(), notify.paidAt()),
                        null);
    }

    @Test
    void repeatedCallbackWritesNothing() {
        when(paymentMapper.markSuccess("P1", "T1")).thenReturn(0);

        service.onPaid(notify);

        verify(outboxWriter, never()).append(anyString(), anyString(), any(), any());
    }
}
