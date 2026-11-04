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

/** mall-app → payment-service 的只读查询：经 payment-query 熔断器，失败最多重试 1 次并随机退避 20–80ms。 */
@Component
public class PaymentQueryClient {

    private final PaymentApi paymentApi;
    private final CircuitBreaker circuitBreaker;

    public PaymentQueryClient(PaymentApi paymentApi, CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.paymentApi = paymentApi;
        this.circuitBreaker = circuitBreakerFactory.create("payment-query");
    }

    @Retryable(includes = ResourceAccessException.class, maxRetries = 1, delay = 20, jitter = 60)
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
