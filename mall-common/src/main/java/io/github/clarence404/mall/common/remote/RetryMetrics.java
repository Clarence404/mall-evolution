package io.github.clarence404.mall.common.remote;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.resilience.retry.MethodRetryEvent;
import org.springframework.stereotype.Component;

/**
 * 只读查询的重试次数（Spring Framework 7 的 {@code @Retryable}）：框架在每次尝试失败、以及最终放弃时各发一个
 * {@link MethodRetryEvent}，这里记成两个指标：
 *
 * <ul>
 *   <li>{@code remote_retryable_failures_total{method, exception}}：每失败一次尝试计 1（含第一次和最后一次）
 *   <li>{@code remote_retryable_exhausted_total{method}}：重试次数用完仍失败、最终放弃的调用数
 * </ul>
 *
 * <p>和下游收到的请求数一起看，就能算出重试把流量放大了多少（重试风暴实验的核心指标）；平时它们应当接近 0，
 * 持续上升说明下游在抖、重试正在替它扛。
 */
@Component
public class RetryMetrics {

    static final String FAILURES = "remote.retryable.failures";
    static final String EXHAUSTED = "remote.retryable.exhausted";

    private final MeterRegistry registry;

    public RetryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onRetryableFailure(MethodRetryEvent event) {
        String method = event.getMethod().getDeclaringClass().getSimpleName() + "."
                + event.getMethod().getName();
        if (event.isRetryAborted()) { // 放弃时的那个事件，failure 是框架包的 RetryException，不再按异常类型分
            Counter.builder(EXHAUSTED)
                    .description("带 @Retryable 的方法重试用完仍失败、最终放弃的次数")
                    .tag("method", method)
                    .register(registry)
                    .increment();
            return;
        }
        Counter.builder(FAILURES)
                .description("带 @Retryable 的方法每失败一次尝试计 1（含第一次和最后一次）")
                .tag("method", method)
                .tag("exception", event.getFailure().getClass().getSimpleName())
                .register(registry)
                .increment();
    }
}
