package io.github.clarence404.mall.common.remote;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * 下游服务返回的错误（ProblemDetail），在 HTTP 接口客户端组里统一转成这个异常，保留下游的 {@code code}。
 *
 * <p>全局异常处理器按 code 透传 4xx，这样 STOCK_INSUFFICIENT 能原样回到前端；5xx 一律转成 SYSTEM_ERROR，不外泄下游细节。
 */
public class RemoteBizException extends RuntimeException {

    private final HttpStatusCode status;
    private final transient ProblemDetail problem;

    public RemoteBizException(HttpStatusCode status, ProblemDetail problem) {
        super(problem == null ? status.toString() : problem.getDetail());
        this.status = status;
        this.problem = problem;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public ProblemDetail getProblem() {
        return problem;
    }

    /** 下游的业务码，如 STOCK_INSUFFICIENT；下游没给时为 null。 */
    public String code() {
        if (problem == null || problem.getProperties() == null) {
            return null;
        }
        Object code = problem.getProperties().get("code");
        return code == null ? null : code.toString();
    }
}
