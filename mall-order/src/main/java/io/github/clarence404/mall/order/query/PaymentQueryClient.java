package io.github.clarence404.mall.order.query;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.error.CommonErrorCode;
import io.github.clarence404.mall.payment.api.PaymentApi;
import io.github.clarence404.mall.payment.api.PaymentStatusView;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * mall-app → payment-service 的只读查询：经 payment-query 熔断器，失败最多重试 1 次并随机退避 20–80ms。
 *
 * <p>重试参数可以用 {@code mall.remote.payment-query.retry.*} 覆盖（次数、基础退避、随机抖动、退避倍数），默认值就是上面这组，
 * 平时不用配；重试风暴实验（experiments/b-retry-storm）用它们复现「不退避、多次重试」的对照组。
 */
@Component
public class PaymentQueryClient {

    private final PaymentApi paymentApi;
    private final CircuitBreaker circuitBreaker;

    public PaymentQueryClient(PaymentApi paymentApi, CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.paymentApi = paymentApi;
        this.circuitBreaker = circuitBreakerFactory.create("payment-query");
    }

    @Retryable(
            includes = ResourceAccessException.class,
            maxRetriesString = "${mall.remote.payment-query.retry.max-retries:1}",
            delayString = "${mall.remote.payment-query.retry.delay:20ms}",
            jitterString = "${mall.remote.payment-query.retry.jitter:60ms}",
            multiplierString = "${mall.remote.payment-query.retry.multiplier:1}")
    public PaymentStatusView query(String orderNo) {
        return circuitBreaker.run(() -> paymentApi.query(orderNo), t -> {
            if (t instanceof CallNotPermittedException || t instanceof BulkheadFullException) {
                throw new BizException(CommonErrorCode.SYSTEM_BUSY);
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(t);
        });
    }
}
