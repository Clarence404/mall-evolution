package io.github.clarence404.mall.common.error;

import org.springframework.http.HttpStatus;

/**
 * mall-common 的通用错误码，不带模块前缀。
 */
public enum CommonErrorCode implements ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    SYSTEM_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后再试");

    private final HttpStatus status;
    private final String title;

    CommonErrorCode(HttpStatus status, String title) {
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
