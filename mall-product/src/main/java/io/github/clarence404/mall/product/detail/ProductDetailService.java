package io.github.clarence404.mall.product.detail;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.clarence404.mall.product.domain.ProductMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 商品详情两级缓存：L1 Caffeine（每实例一份，写入后 30 秒，最多 1 万条）+ L2 Redis（30 分钟 + 0–5 分钟随机）。
 *
 * <p>Cache Aside 的读路径；写路径的失效见 {@link ProductCacheInvalidator}。下单计价不经过这里，直接查 t_product。
 */
@Slf4j
@Service
public class ProductDetailService {

    static final String KEY_PREFIX = "product:detail:";
    private static final String NULL_JSON = "{}"; // 空值占位，防穿透

    private final Cache<Long, Optional<ProductDetailView>> local = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30)) // 兜底丢失的失效广播
            .recordStats()
            .build();

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final ProductMapper productMapper;
    private final Counter l2Hit;
    private final Counter l2Miss;
    private final Counter l2Null;
    private final Counter dbLoad;
    private DetailSourceListener sourceListener = DetailSourceListener.NONE;
    private DetailCachePolicy policy = DetailCachePolicy.DEFAULT;

    public ProductDetailService(
            StringRedisTemplate redis, JsonMapper jsonMapper, ProductMapper productMapper, MeterRegistry registry) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.productMapper = productMapper;
        CaffeineCacheMetrics.monitor(registry, local, "product_detail"); // cache.gets{result=hit|miss}
        this.l2Hit = registry.counter("product.detail.l2", "result", "hit");
        this.l2Miss = registry.counter("product.detail.l2", "result", "miss");
        this.l2Null = registry.counter("product.detail.l2", "result", "null");
        this.dbLoad = registry.counter("product.detail.db.load"); // 回源次数，超过 100 QPS 告警
    }

    /** 只有演示环境（demo profile）注册了监听器，用来在响应头里标出这次命中的是哪一层。 */
    @Autowired(required = false)
    void setSourceListener(DetailSourceListener sourceListener) {
        this.sourceListener = sourceListener;
    }

    /** 只有演示环境（demo profile）注册了实现，用来逐层关掉缓存或保护（高并发实验 F）；正式环境全部打开。 */
    @Autowired(required = false)
    void setPolicy(DetailCachePolicy policy) {
        this.policy = policy;
    }

    public Optional<ProductDetailView> get(long skuId) {
        DetailSource[] source = {DetailSource.L1}; // 加载函数没被调用，就是 L1 命中
        Optional<ProductDetailView> view;
        if (policy.l1Enabled() && policy.singleFlightEnabled()) {
            // 同一 skuId 并发未命中时，本 JVM 只有一个线程去查 Redis / 数据库。加载函数返回 null 时 Caffeine 不缓存（只有演示环境关掉空值缓存才会）
            Optional<ProductDetailView> cached = local.get(skuId, id -> {
                Optional<ProductDetailView> loaded = loadFromRemote(id, source);
                return cacheableInL1(loaded) ? loaded : null;
            });
            view = cached != null ? cached : Optional.empty();
        } else if (policy.l1Enabled()) {
            // 仅演示环境：不单飞。并发未命中的请求各自加载、各自回填
            view = local.getIfPresent(skuId);
            if (view == null) {
                view = loadFromRemote(skuId, source);
                if (cacheableInL1(view)) {
                    local.put(skuId, view);
                }
            }
        } else {
            view = loadFromRemote(skuId, source); // 仅演示环境：不用本机缓存
        }
        sourceListener.onLookup(skuId, source[0]);
        return view;
    }

    /** 空结果（SKU 不存在）要不要进 L1：正式环境一律缓存。 */
    private boolean cacheableInL1(Optional<ProductDetailView> view) {
        return view.isPresent() || policy.nullCacheEnabled();
    }

    private Optional<ProductDetailView> loadFromRemote(long skuId, DetailSource[] source) {
        source[0] = DetailSource.DB;
        if (!policy.l2Enabled()) {
            return Optional.ofNullable(selectFromDb(skuId)); // 仅演示环境：不读也不写 Redis
        }
        String key = KEY_PREFIX + skuId;
        String json;
        try {
            json = redis.opsForValue().get(key);
        } catch (DataAccessException | SerializationException e) {
            // 缓存出问题宁可回源：连不上、超时（QueryTimeoutException）、命令报错（RedisSystemException）都是
            // DataAccessException，一律直接查库并跳过回写，不把 Redis 故障变成接口 500
            log.warn("读取 Redis 失败，直接回源，skuId={}", skuId, e);
            return Optional.ofNullable(selectFromDb(skuId));
        }
        if (json != null) {
            if (NULL_JSON.equals(json)) {
                l2Null.increment();
                source[0] = DetailSource.L2;
                return Optional.empty();
            }
            try {
                ProductDetailView cached = jsonMapper.readValue(json, ProductDetailView.class);
                l2Hit.increment();
                source[0] = DetailSource.L2;
                return Optional.of(cached);
            } catch (JacksonException e) {
                // 缓存里的值读不出来（多半是 ProductDetailView 改过结构）：按未命中回源，下面会用新结构覆盖
                log.warn("商品详情缓存反序列化失败，按未命中回源，skuId={}", skuId, e);
            }
        }
        l2Miss.increment();
        ProductDetailView view = selectFromDb(skuId);
        try {
            if (view == null) {
                if (policy.nullCacheEnabled()) { // 只有演示环境会关
                    redis.opsForValue().set(key, NULL_JSON, Duration.ofSeconds(60));
                }
                return Optional.empty();
            }
            Duration ttl = Duration.ofMinutes(30)
                    .plusSeconds(ThreadLocalRandom.current().nextInt(300));
            redis.opsForValue().set(key, jsonMapper.writeValueAsString(view), ttl);
        } catch (DataAccessException | SerializationException | JacksonException e) {
            log.warn("回写 Redis 失败，跳过，skuId={}", skuId, e);
        }
        return Optional.ofNullable(view);
    }

    /** 回源：只查 t_product。 */
    private ProductDetailView selectFromDb(long skuId) {
        dbLoad.increment();
        return policy.aroundDbLoad(() -> productMapper.selectDetail(skuId)); // 正式环境就是直接查
    }

    void evictLocal(long skuId) {
        local.invalidate(skuId);
    }
}
