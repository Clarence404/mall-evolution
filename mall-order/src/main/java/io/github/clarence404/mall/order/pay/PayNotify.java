package io.github.clarence404.mall.order.pay;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 验签通过的渠道支付成功通知，补上支付单里的订单信息。 */
public record PayNotify(
        String paymentNo,
        String channelTradeNo,
        long orderId,
        String orderNo,
        long userId,
        BigDecimal amount,
        LocalDateTime paidAt) {}
