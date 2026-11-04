package io.github.clarence404.mall.payment.api;

import java.time.LocalDateTime;

/** 订单的支付状态：NONE 未发起、PENDING 待支付、PAID 已支付、REFUNDING 退款中、REFUNDED 已退款。 */
public record PaymentStatusView(String orderNo, String status, LocalDateTime paidAt) {}
