package io.github.clarence404.mall.product.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.inventory.api.StockView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.ResourceAccessException;

/**
 * 详情页「有货」提示的三层：本机 Caffeine（2 秒）→ Redis（10 秒）→ 库存服务；库存服务不可用时按有货展示，并把这个兜底结果在本机缓存 5 秒。
 * 用可拨动的假时钟验证过期，不 sleep。
 */
class StockDisplayServiceTest {

    static final long SKU = 20001L;
    static final String KEY = "stock:display:20001";

    StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);

    StockQueryService stockQueryService = mock(StockQueryService.class);
    AtomicLong nanos = new AtomicLong(); // 假时钟：Caffeine 按它判断过期
    StockDisplayService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        service = new StockDisplayService(redis, stockQueryService, nanos::get);
    }

    @Test
    void missEverywhereAsksInventoryAndWritesRedisWithTenSecondTtl() {
        when(stockQueryService.listStocks(List.of(SKU))).thenReturn(List.of(new StockView(SKU, 3)));

        assertThat(service.inStock(SKU)).isTrue();

        verify(ops).set(KEY, "1", Duration.ofSeconds(10));
    }

    /** 热点商品每秒上千次详情请求：2 秒内同一台机器只读一次 Redis，其余都在本机内存里命中。 */
    @Test
    void localCacheAbsorbsRepeatedReadsForTwoSeconds() {
        when(ops.get(KEY)).thenReturn("0");

        for (int i = 0; i < 50; i++) {
            assertThat(service.inStock(SKU)).isFalse();
        }
        verify(ops, times(1)).get(KEY);
        verify(stockQueryService, never()).listStocks(any());

        advance(Duration.ofMillis(2100)); // 本机缓存过期，再读一次 Redis
        service.inStock(SKU);
        verify(ops, times(2)).get(KEY);
    }

    /** 库存服务不可用：按有货展示（下单时再以预占结果为准），而且 5 秒内不再去撞库存服务。 */
    @Test
    void inventoryFailureFallsBackToInStockAndIsCachedLocallyForFiveSeconds() {
        when(stockQueryService.listStocks(any())).thenThrow(new ResourceAccessException("Read timed out"));

        for (int i = 0; i < 20; i++) {
            assertThat(service.inStock(SKU)).isTrue();
        }
        verify(stockQueryService, times(1)).listStocks(any());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class)); // 兜底值不写进 Redis，免得别的机器也当真

        advance(Duration.ofMillis(4000)); // 兜底值比正常值缓存得久：4 秒时仍不重试
        service.inStock(SKU);
        verify(stockQueryService, times(1)).listStocks(any());

        advance(Duration.ofMillis(1100)); // 过了 5 秒才再试一次
        service.inStock(SKU);
        verify(stockQueryService, times(2)).listStocks(any());
    }

    /** Redis 与本机缓存同时过期的瞬间，同一台机器上并发的请求只有一个去查库存服务（单飞），其余等它的结果。 */
    @Test
    void concurrentMissesOnTheSameSkuLoadOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(stockQueryService.listStocks(List.of(SKU))).thenAnswer(inv -> {
            entered.countDown();
            release.await(); // 卡住第一个加载的线程，让其余线程都撞上来
            return List.of(new StockView(SKU, 1));
        });

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                calls.add(() -> service.inStock(SKU));
            }
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> c : calls) {
                futures.add(pool.submit(c));
            }
            entered.await();
            Thread.sleep(100); // 给其余 15 个线程时间走到缓存的加载锁上
            release.countDown();
            for (Future<Boolean> f : futures) {
                assertThat(f.get()).isTrue();
            }
        }
        verify(stockQueryService, times(1)).listStocks(List.of(SKU));
    }

    /** Redis 超时不影响结果：直接问库存服务，回写失败也只是跳过。 */
    @Test
    void redisFailureGoesStraightToInventory() {
        when(ops.get(KEY)).thenThrow(new QueryTimeoutException("Redis command timed out"));
        when(stockQueryService.listStocks(List.of(SKU))).thenReturn(List.of(new StockView(SKU, 0)));

        assertThat(service.inStock(SKU)).isFalse();
    }

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }
}
