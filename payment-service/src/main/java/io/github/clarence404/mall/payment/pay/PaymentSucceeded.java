package io.github.clarence404.mall.payment.pay;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** payment-succeeded 消息体：支付单置成功的同一事务写入 payment-service 自己库里的 t_outbox。 */
public record PaymentSucceeded(
        String orderNo, String paymentNo, long userId, BigDecimal amount, LocalDateTime paidAt) {}
