package io.github.clarence404.mall.common.error;

import io.github.clarence404.mall.common.remote.RemoteBizException;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 失败统一返回 RFC 9457 的 ProblemDetail，扩展 code（稳定的业务码）与 traceId。
 *
 * <p>Spring MVC 自身的异常（缺请求头、请求体解析失败、校验失败）由父类转成 4xx，这里只额外处理业务异常和兜底异常。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String PROBLEM_BASE = "https://api.mall.example/problems/";

    @ExceptionHandler(BizException.class)
    public ProblemDetail handleBiz(BizException ex, HttpServletResponse response) {
        ErrorCode ec = ex.getErrorCode();
        if (ec == CommonErrorCode.SYSTEM_BUSY) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "2"); // App 按 Retry-After 等待后再让用户重试
            log.warn("系统繁忙，快速失败：{}", ex.getMessage());
            return problem(ec, ec.title());
        }
        if (ec.status().is5xxServerError()) {
            log.error("业务处理失败，code={}", ec.code(), ex);
            return problem(ec, ec.title()); // 5xx 不把内部信息给客户端
        }
        log.warn("业务异常，code={}, message={}", ec.code(), ex.getMessage());
        return problem(ec, ex.getMessage());
    }

    /** 下游返回的业务错误：4xx 按 code 原样透传（如 STOCK_INSUFFICIENT），5xx 统一转成 SYSTEM_ERROR。 */
    @ExceptionHandler(RemoteBizException.class)
    public ProblemDetail handleRemote(RemoteBizException ex) {
        ProblemDetail remote = ex.getProblem();
        if (ex.getStatus().is5xxServerError() || remote == null || ex.code() == null) {
            log.error("下游服务调用失败，status={}", ex.getStatus(), ex);
            return problem(CommonErrorCode.SYSTEM_ERROR, CommonErrorCode.SYSTEM_ERROR.title());
        }
        log.warn("下游业务异常，code={}, detail={}", ex.code(), remote.getDetail());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), remote.getDetail());
        pd.setType(remote.getType());
        pd.setTitle(remote.getTitle());
        pd.setProperty("code", ex.code());
        pd.setProperty("traceId", currentTraceId());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnknown(Exception ex) {
        log.error("未处理的异常", ex);
        return problem(CommonErrorCode.SYSTEM_ERROR, CommonErrorCode.SYSTEM_ERROR.title());
    }

    /** 请求体校验失败：字段错误放进 errors 扩展字段。 */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = problem(CommonErrorCode.VALIDATION_FAILED, "请求参数校验失败");
        pd.setProperty(
                "errors",
                ex.getBindingResult().getFieldErrors().stream()
                        .map(fe -> Map.of(
                                "field", fe.getField(),
                                "message", String.valueOf(fe.getDefaultMessage())))
                        .toList());
        return ResponseEntity.badRequest().body(pd);
    }

    /** 方法参数校验失败（如 Idempotency-Key 的长度、请求体的分组约束）。 */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = problem(CommonErrorCode.VALIDATION_FAILED, "请求参数校验失败");
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> Map.of(
                                "field",
                                errorField(r.getMethodParameter().getParameterName(), e),
                                "message",
                                String.valueOf(e.getDefaultMessage()))))
                .toList();
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    /** 父类生成的 ProblemDetail（缺请求头、JSON 解析失败等）也补上 traceId，400 统一给 VALIDATION_FAILED。 */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            if (statusCode.value() == 400
                    && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
                pd.setProperty("code", CommonErrorCode.VALIDATION_FAILED.code());
            }
            pd.setProperty("traceId", currentTraceId());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    public static ProblemDetail problem(ErrorCode ec, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ec.status(), detail);
        pd.setType(URI.create(PROBLEM_BASE + ec.code().toLowerCase().replace('_', '-')));
        pd.setTitle(ec.title());
        pd.setProperty("code", ec.code());
        pd.setProperty("traceId", currentTraceId());
        return pd;
    }

    static String currentTraceId() {
        return MDC.get(TraceIdFilter.MDC_KEY);
    }

    private static String errorField(@Nullable String parameterName, Object error) {
        if (error instanceof org.springframework.validation.FieldError fe) {
            return fe.getField();
        }
        return parameterName == null ? "" : parameterName;
    }
}
