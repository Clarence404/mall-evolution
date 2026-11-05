package io.github.clarence404.mall.inventory.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 仅演示环境：开关打开时，reserve 请求先睡一段时间再进 Controller，模拟库存服务变慢。
 *
 * <p>延迟发生在处理之前：订单侧 500ms 读超时后按「结果未知」立即 release，release 先到、reserve 后到，
 * 正好走一遍空回滚与防悬挂。
 */
@Component
@Profile("demo")
@RequiredArgsConstructor
class DemoReserveDelayFilter extends OncePerRequestFilter {

    static final String RESERVE_PATH = "/internal/inventory/reserve";

    private final DemoFaults faults;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && RESERVE_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int delay = faults.getReserveDelayMs();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        chain.doFilter(request, response);
    }
}
