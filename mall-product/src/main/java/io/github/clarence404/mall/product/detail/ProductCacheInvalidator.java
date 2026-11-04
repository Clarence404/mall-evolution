package io.github.clarence404.mall.product.detail;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Cache Aside 的写路径：事务提交后删 L2 并广播删 L1，1 秒后再删一次（延迟双删），所有 key 都带 TTL 兜底。
 *
 * <p>明确接受的风险：第二次删除用进程内定时任务执行，进程恰好在这 1 秒内重启会丢，由 30 分钟 TTL 兜底。
 */
@Slf4j
@Component
public class ProductCacheInvalidator {

    static final String CHANNEL = "cache:invalidate:product";
    private static final ScheduledExecutorService DELAYED =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    private final StringRedisTemplate redis;

    public ProductCacheInvalidator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(ProductChangedEvent e) {
        evict(e.skuId());
        DELAYED.schedule(() -> evict(e.skuId()), 1, TimeUnit.SECONDS); // 延迟双删
    }

    /** 运营发现价格未生效时，可在后台手动刷新。 */
    public void evictNow(long skuId) {
        evict(skuId);
    }

    private void evict(long skuId) {
        try {
            redis.delete(ProductDetailService.KEY_PREFIX + skuId); // 删 L2
            redis.convertAndSend(CHANNEL, String.valueOf(skuId)); // 通知所有实例删 L1
        } catch (RuntimeException ex) {
            log.warn("商品缓存删除失败，等待 TTL 兜底，skuId={}", skuId, ex);
        }
    }
}
