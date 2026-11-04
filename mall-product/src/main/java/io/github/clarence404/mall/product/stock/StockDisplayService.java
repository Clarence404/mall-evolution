package io.github.clarence404.mall.product.stock;

import io.github.clarence404.mall.inventory.api.StockView;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 库存展示：详情页的「有货」只是提示，放进 Redis 设 10 秒 TTL；真正扣减仍是数据库条件更新，不读这里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDisplayService {

    static final String KEY_PREFIX = "stock:display:";
    private static final Duration TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final StockQueryService stockQueryService;

    public boolean inStock(long skuId) {
        String key = KEY_PREFIX + skuId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return "1".equals(cached);
            }
        } catch (RuntimeException e) {
            log.warn("读取库存展示缓存失败，直接查库存，skuId={}", skuId, e);
        }
        boolean inStock;
        try {
            inStock = stockQueryService.listStocks(List.of(skuId)).stream().anyMatch(StockView::inStock);
        } catch (RuntimeException e) {
            log.warn("查询库存服务失败（已重试 1 次），按有货展示，下单时再以预占结果为准，skuId={}", skuId, e);
            return true;
        }
        try {
            redis.opsForValue().set(key, inStock ? "1" : "0", TTL);
        } catch (RuntimeException e) {
            log.warn("回写库存展示缓存失败，skuId={}", skuId, e);
        }
        return inStock;
    }
}
