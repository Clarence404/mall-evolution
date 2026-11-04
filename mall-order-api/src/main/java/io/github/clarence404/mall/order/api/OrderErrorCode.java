package io.github.clarence404.mall.order.api;

import io.github.clarence404.mall.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "订单不存在"),
    ORDER_STATUS_CONFLICT(HttpStatus.CONFLICT, "订单状态不允许此操作"),
    ORDER_SKU_OFF_SHELF(HttpStatus.UNPROCESSABLE_CONTENT, "商品已下架"),
    /** 下单频控：同一用户同一 SKU 的待支付订单最多 2 单。 */
    ORDER_PENDING_LIMIT(HttpStatus.CONFLICT, "您有未支付的订单，请先完成支付");

    private final HttpStatus status;
    private final String title;

    OrderErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String code() {
        return name(); // 业务码 = 枚举名
    }
}
