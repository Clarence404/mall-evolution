package io.github.clarence404.mall.order.paid;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** order-payment-consumer 收到的 payment-succeeded 消息（订单侧自己的类型，按 JSON 字段约定反序列化）。 */
public record PaymentSucceeded(
        String orderNo, String paymentNo, long userId, BigDecimal amount, LocalDateTime paidAt) {}
