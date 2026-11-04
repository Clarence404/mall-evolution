package io.github.clarence404.mall.order.api;

/** markPaid 的结果：重复调用返回 ALREADY_PAID；订单已关闭返回 ORDER_CANCELLED，支付服务据此走原路退款。 */
public enum MarkPaidResult {
    PAID,
    ALREADY_PAID,
    ORDER_CANCELLED
}
