package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.ReleaseStockRequest;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.api.ReserveStockRequest;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;

/**
 * 对 InventoryApi 的一层包装：reserve / release 经过 inventory-reserve 熔断器与信号量舱壁，异常原样抛出。
 *
 * <p>熔断打开抛 CallNotPermittedException，舱壁满抛 BulkheadFullException——都表示请求根本没发出去。
 */
@Component
public class InventoryReserveClient {

    private final InventoryApi inventoryApi;
    private final CircuitBreaker circuitBreaker;

    public InventoryReserveClient(InventoryApi inventoryApi, CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.inventoryApi = inventoryApi;
        this.circuitBreaker = circuitBreakerFactory.create("inventory-reserve");
    }

    public ReserveResult reserve(ReserveStockRequest req) {
        return circuitBreaker.run(() -> inventoryApi.reserve(req), InventoryReserveClient::rethrow);
    }

    public void release(ReleaseStockRequest req) {
        circuitBreaker.run(
                () -> {
                    inventoryApi.release(req);
                    return null;
                },
                InventoryReserveClient::rethrow);
    }

    /** Spring Cloud CircuitBreaker 没有 fallback 时会包一层 NoFallbackAvailableException，这里原样抛出原始异常。 */
    private static <T> T rethrow(Throwable t) {
        while ((t instanceof java.util.concurrent.ExecutionException
                        || t instanceof java.util.concurrent.CompletionException)
                && t.getCause() != null) {
            t = t.getCause(); // 防御：若误配成线程池舱壁，异常会被包一层
        }
        if (t instanceof RuntimeException re) {
            throw re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        throw new IllegalStateException(t);
    }
}
