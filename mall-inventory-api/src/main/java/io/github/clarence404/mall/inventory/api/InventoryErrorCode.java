package io.github.clarence404.mall.inventory.api;

import io.github.clarence404.mall.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum InventoryErrorCode implements ErrorCode {
    STOCK_INSUFFICIENT(HttpStatus.CONFLICT, "库存不足");

    private final HttpStatus status;
    private final String title;

    InventoryErrorCode(HttpStatus status, String title) {
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
        return name();
    }
}
