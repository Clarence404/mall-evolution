package io.github.clarence404.mall.order.cancel;

/** order-timeout 定时消息体：到期后 order-timeout-consumer 按订单号关单。 */
public record OrderTimeoutMessage(String orderNo) {}
