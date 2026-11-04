package io.github.clarence404.mall.payment.pay;

import io.github.clarence404.mall.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 支付错误码：单独一个枚举，不并进 OrderErrorCode；阶段三支付独立成服务时连包带枚举一起搬走，前缀 PAY_ 不变。
 */
public enum PayErrorCode implements ErrorCode {
    PAY_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "支付金额与订单不一致"),
    PAY_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "渠道回调签名无效"),
    PAY_PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "支付单不存在");

    private final HttpStatus status;
    private final String title;

    PayErrorCode(HttpStatus status, String title) {
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
