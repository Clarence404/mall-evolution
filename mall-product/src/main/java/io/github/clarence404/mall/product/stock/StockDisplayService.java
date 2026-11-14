package io.github.clarence404.mall.product.stock;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.clarence404.mall.inventory.api.StockView;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 库存展示：详情页的「有货」只是提示，真正扣减仍是数据库条件更新，不读这里。详情页是最热的读接口，所以分三层：
 *
 * <ol>
 *   <li>本机 Caffeine，写入后 2 秒：热点商品的请求绝大多数在内存里命中，不再每次都同步访问 Redis
 *   <li>Redis，10 秒 TTL：各实例共用，把对库存服务的调用压到「每个 SKU 每 10 秒一次」的量级
 *   <li>库存服务（{@link StockQueryService}：重试 1 次 + 熔断 + 舱壁）
 * </ol>
 *
 * <p>两个保护：
 *
 * <ul>
 *   <li>单飞：同一个 SKU 在本机缓存里未命中时，Caffeine 保证本 JVM 只有一个线程去加载，其余线程等它的结果，
 *       缓存过期的瞬间不会有一批请求同时打到 Redis 和库存服务
 *   <li>兜底也缓存：库存服务不可用（超时、熔断打开、舱壁满）时按「有货」展示，这个兜底值在本机缓存 5 秒、不写 Redis——
 *       故障期间每个 SKU 每台机器 5 秒才试一次，不会让每个详情请求都卡一次超时
 * </ul>
 */
@Slf4j
@Service
public class StockDisplayService {

    static final String KEY_PREFIX = "stock:display:";
    private static final Duration TTL = Duration.ofSeconds(10);
    /** 本机缓存：正常结果保留 2 秒，展示最多比 Redis 再晚 2 秒，换来热点路径不碰网络。 */
    private static final Duration LOCAL_TTL = Duration.ofSeconds(2);
    /** 库存服务不可用时的兜底结果在本机保留 5 秒：比正常值久，故障期间少撞几次。 */
    private static final Duration FALLBACK_TTL = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final StockQueryService stockQueryService;
    private final Cache<Long, Hint> local;

    @Autowired
    public StockDisplayService(StringRedisTemplate redis, StockQueryService stockQueryService) {
        this(redis, stockQueryService, System::nanoTime);
    }

    /** 测试用：传入可拨动的时钟（纳秒），不用 sleep 就能验证过期。 */
    StockDisplayService(StringRedisTemplate redis, StockQueryService stockQueryService, LongSupplier nanoClock) {
        this.redis = redis;
        this.stockQueryService = stockQueryService;
        this.local = Caffeine.newBuilder()
                .maximumSize(10_000)
                .ticker(nanoClock::getAsLong)
                .expireAfter(Expiry.<Long, Hint>creating((skuId, hint) -> hint.fallback() ? FALLBACK_TTL : LOCAL_TTL))
                .build();
    }

    public boolean inStock(long skuId) {
        // get(key, 加载函数)：同一个 key 并发未命中时只有一个线程执行加载函数（单飞）
        return local.get(skuId, this::load).inStock();
    }

    /** 本机未命中：先读 Redis，再问库存服务。任何一步出错都不抛给调用方——详情页不能因为一个提示而失败。 */
    private Hint load(long skuId) {
        String key = KEY_PREFIX + skuId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return new Hint("1".equals(cached), false);
            }
        } catch (RuntimeException e) {
            log.warn("读取库存展示缓存失败，直接查库存，skuId={}", skuId, e);
        }
        boolean inStock;
        try {
            inStock = stockQueryService.listStocks(List.of(skuId)).stream().anyMatch(StockView::inStock);
        } catch (RuntimeException e) {
            // 超时（已重试 1 次）、熔断打开、舱壁满都走这里：按有货展示，下单时再以预占结果为准
            log.warn("查询库存服务失败，按有货展示并在本机缓存 {} 秒，skuId={}，原因：{}", FALLBACK_TTL.toSeconds(), skuId, e.toString());
            return new Hint(true, true);
        }
        try {
            redis.opsForValue().set(key, inStock ? "1" : "0", TTL);
        } catch (RuntimeException e) {
            log.warn("回写库存展示缓存失败，skuId={}", skuId, e);
        }
        return new Hint(inStock, false);
    }

    /**
     * 本机缓存里的一条提示。
     *
     * @param inStock 是否展示「有货」
     * @param fallback true 表示这是库存服务不可用时的兜底值（缓存得更久，且不写 Redis）
     */
    private record Hint(boolean inStock, boolean fallback) {}
}
