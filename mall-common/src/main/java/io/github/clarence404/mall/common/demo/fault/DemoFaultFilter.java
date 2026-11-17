package io.github.clarence404.mall.common.demo.fault;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 仅演示环境：按 {@link DemoFaultRegistry} 里的规则给请求注入故障（排队、变慢、失败、响应迟到）。没有规则时只多一次空 Map 判断。
 *
 * <p>排在 Spring Boot 的 HTTP 观测过滤器（HIGHEST_PRECEDENCE + 1）之后、安全过滤链之前：注入的排队、延迟和失败都会计进
 * http_server_requests 指标（和真实的「服务变慢」在监控上看起来一样），延迟和排队也会占住 Tomcat 线程。
 * 生产不注册（demo profile 与 prod 互斥，见 ProdDemoProfileGuard）。
 */
@Component
@Profile("demo")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DemoFaultFilter extends OncePerRequestFilter {

    /** 被故障规则直接判失败的响应带这个头，值是规则名，抓包或看压测报告时一眼能分出「注入的失败」和「真实的失败」。 */
    public static final String HEADER = "X-Demo-Fault";

    private final DemoFaultRegistry registry;

    public DemoFaultFilter(DemoFaultRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        DemoFault fault = registry.match(request.getMethod(), path);
        if (fault == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            fault.enter();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        try {
            DemoFaultRule rule = fault.rule();
            boolean delayed = fault.rollDelay();
            sleep(delayed ? rule.delayMs() : 0);
            if (fault.rollFailure()) {
                fault.recordFailed();
                writeFailure(response, rule);
                return;
            }
            fault.recordPassed();
            if (delayed && rule.responseDelayMs() > 0) {
                // 先把响应留在内存里，业务处理完再等一会儿才真正发出去：服务端已经成功，调用方却迟迟收不到
                ContentCachingResponseWrapper held = new ContentCachingResponseWrapper(response);
                chain.doFilter(request, held);
                sleep(rule.responseDelayMs());
                held.copyBodyToResponse();
            } else {
                chain.doFilter(request, response);
            }
        } finally {
            fault.exit();
        }
    }

    private static void writeFailure(HttpServletResponse response, DemoFaultRule rule) throws IOException {
        response.setStatus(rule.failStatus());
        response.setHeader(HEADER, rule.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("{\"title\":\"演示故障\",\"status\":" + rule.failStatus()
                        + ",\"code\":\"DEMO_FAULT\",\"detail\":\"故障规则 " + rule.name() + " 注入的失败\"}");
    }

    private static void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
