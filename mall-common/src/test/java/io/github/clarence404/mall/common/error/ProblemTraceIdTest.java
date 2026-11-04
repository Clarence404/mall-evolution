package io.github.clarence404.mall.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/** ProblemDetail.traceId 取自 OpenTelemetry 当前 Span：有 agent 时是请求的 Trace ID，没挂 agent（本地、测试）时为 null。 */
class ProblemTraceIdTest {

    @Test
    void traceIdIsNullWithoutAgent() {
        ProblemDetail pd = GlobalExceptionHandler.problem(CommonErrorCode.SYSTEM_ERROR, "boom");

        assertThat(pd.getProperties()).containsEntry("traceId", null).containsEntry("code", "SYSTEM_ERROR");
    }

    @Test
    void traceIdComesFromCurrentSpan() {
        SpanContext span = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        try (Scope ignored = Context.current().with(Span.wrap(span)).makeCurrent()) {
            ProblemDetail pd = GlobalExceptionHandler.problem(CommonErrorCode.SYSTEM_ERROR, "boom");

            assertThat(pd.getProperties()).containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c");
        }
    }
}
