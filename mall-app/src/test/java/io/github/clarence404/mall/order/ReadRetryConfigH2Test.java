package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.order.query.PaymentQueryClient;
import io.github.clarence404.mall.support.H2TestBase;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.ResourceAccessException;

/**
 * 只读查询的重试参数可以从配置覆盖（重试风暴实验的对照组靠它）：这里把支付查询改成「重试 3 次、不退避」，
 * 下游一直超时时一共调 4 次，每次失败都记进 remote_retryable_failures_total，最终放弃再记一次 remote_retryable_exhausted_total。默认值（重试 1 次、随机退避）不变，
 * 在正式配置里不出现这几项。
 */
@TestPropertySource(
        properties = {
            "mall.remote.payment-query.retry.max-retries=3",
            "mall.remote.payment-query.retry.delay=0ms",
            "mall.remote.payment-query.retry.jitter=0ms"
        })
class ReadRetryConfigH2Test extends H2TestBase {

    @Autowired
    PaymentQueryClient paymentQueryClient;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @AfterEach
    void resetBreaker() {
        circuitBreakerRegistry.circuitBreaker("payment-query").reset();
    }

    @Test
    void retriesComeFromConfiguration() {
        when(paymentApi.query(anyString())).thenThrow(new ResourceAccessException("模拟读超时"));

        assertThatThrownBy(() -> paymentQueryClient.query("8001")).isInstanceOf(ResourceAccessException.class);

        verify(paymentApi, times(4)).query("8001"); // 1 次 + 重试 3 次
        assertThat(meterRegistry
                        .get("remote.retryable.failures")
                        .tag("method", "PaymentQueryClient.query")
                        .tag("exception", "ResourceAccessException")
                        .counter()
                        .count())
                .isEqualTo(4);
        assertThat(meterRegistry
                        .get("remote.retryable.exhausted")
                        .tag("method", "PaymentQueryClient.query")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
