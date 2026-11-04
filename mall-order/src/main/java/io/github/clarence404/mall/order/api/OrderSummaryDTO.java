package io.github.clarence404.mall.order.api;

import java.math.BigDecimal;

/**
 * 订单摘要：对外只给订单号，不给主键 id。
 */
public record OrderSummaryDTO(String orderNo, long userId, int status, BigDecimal payAmount) {}
