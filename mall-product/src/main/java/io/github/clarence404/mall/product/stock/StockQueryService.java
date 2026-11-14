package io.github.clarence404.mall.product.stock;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.StockView;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

/**
 * mall-app → inventory-service 的只读查询（详情页「有货」提示）。两层保护，由外到内：
 *
 * <ul>
 *   <li>重试：只读查询只在这一层重试，最多 1 次，随机退避 20–80ms（Spring Framework 7 自带注解）。写接口一律不重试
 *   <li>熔断 + 舱壁（{@value #CIRCUIT_BREAKER}）：库存服务持续超时或 5xx 时熔断打开，请求在本进程内直接失败，不再每次等满超时；
 *       舱壁限制同时在途的查询数，库存服务变慢时不会把 Tomcat 线程占光。用独立的实例，不和下单的 inventory-reserve 共用——
 *       展示查询出问题不该让下单跟着熔断
 * </ul>
 *
 * <p>熔断打开（CallNotPermittedException）与舱壁满（BulkheadFullException）不在重试范围内，原样抛出；
 * 调用方 {@link StockDisplayService} 统一按「有货」兜底并在本机缓存几秒。
 */
@Service
public class StockQueryService {

    static final String CIRCUIT_BREAKER = "inventory-stock-query";

    private final InventoryApi inventoryApi;
    private final CircuitBreaker circuitBreaker;

    public StockQueryService(InventoryApi inventoryApi, CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.inventoryApi = inventoryApi;
        this.circuitBreaker = circuitBreakerFactory.create(CIRCUIT_BREAKER);
    }

    @Retryable(includes = ResourceAccessException.class, maxRetries = 1, delay = 20, jitter = 60)
    public List<StockView> listStocks(List<Long> skuIds) {
        return circuitBreaker.run(() -> inventoryApi.listStocks(skuIds), StockQueryService::rethrow);
    }

    /** Spring Cloud CircuitBreaker 没有 fallback 时会包一层 NoFallbackAvailableException，这里原样抛出原始异常，重试才认得出超时。 */
    private static <T> T rethrow(Throwable t) {
        while ((t instanceof ExecutionException || t instanceof CompletionException) && t.getCause() != null) {
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
