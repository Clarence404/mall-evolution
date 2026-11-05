package io.github.clarence404.mall.common.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DemoTraceIdHeaderFilterTest {

    private final DemoTraceIdHeaderFilter filter = new DemoTraceIdHeaderFilter();

    @Test
    void writesCurrentTraceIdWhenSpanIsValid() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        SpanContext ctx =
                SpanContext.create(traceId, "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault());
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (Scope ignored = Span.wrap(ctx).makeCurrent()) {
            filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
        }
        assertThat(response.getHeader(DemoTraceIdHeaderFilter.HEADER)).isEqualTo(traceId);
    }

    @Test
    void noHeaderWithoutAgent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
        assertThat(response.getHeader(DemoTraceIdHeaderFilter.HEADER)).isNull();
    }
}
