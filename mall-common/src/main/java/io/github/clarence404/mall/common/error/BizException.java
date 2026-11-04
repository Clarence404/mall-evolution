package io.github.clarence404.mall.common.error;

/**
 * 业务异常，只持有 {@link ErrorCode} 接口；由全局异常处理器转成 ProblemDetail。
 */
public class BizException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.title());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
