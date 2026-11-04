package io.github.clarence404.mall.payment.pay;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 验签通过的渠道支付成功通知，补上支付单里的订单号与用户。 */
public record PayNotify(
        String paymentNo,
        String channelTradeNo,
        String orderNo,
        long userId,
        BigDecimal amount,
        LocalDateTime paidAt) {}
