package io.github.clarence404.mall.user.api;

import io.github.clarence404.mall.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum UserErrorCode implements ErrorCode {
    USER_ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "收货地址不存在"),
    USER_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "手机号或密码错误"),
    USER_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "刷新令牌无效或已过期");

    private final HttpStatus status;
    private final String title;

    UserErrorCode(HttpStatus status, String title) {
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
