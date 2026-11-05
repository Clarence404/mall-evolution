package io.github.clarence404.mall.common.demo;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 仅演示环境：把当前 traceId 写进响应头 {@code X-Trace-Id}，成功的请求也能在演示控制台里看到链路 ID
 * （失败的请求本来就在 ProblemDetail 的 traceId 字段里）。
 *
 * <p>traceId 来自 OpenTelemetry Java Agent 在入口建的 Span；没挂 agent 时 Span 无效，不写这个头。生产不注册。
 */
@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoTraceIdHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SpanContext ctx = Span.current().getSpanContext();
        if (ctx.isValid()) {
            response.setHeader(HEADER, ctx.getTraceId());
        }
        chain.doFilter(request, response);
    }
}
