package io.github.clarence404.mall.product.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.product.domain.ProductMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class ProductDetailServiceTest {

    StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);

    ProductMapper mapper = mock(ProductMapper.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProductDetailService service;

    ProductDetailView view = new ProductDetailView("20001", "红心柚", "", "39.90", true);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        service = new ProductDetailService(redis, JsonMapper.builder().build(), mapper, registry);
    }

    @Test
    void missInBothLevelsLoadsFromDbAndWritesL2WithJitteredTtl() {
        when(mapper.selectDetail(20001L)).thenReturn(view);

        assertThat(service.get(20001L)).contains(view);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("product:detail:20001"), anyString(), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofMinutes(30), Duration.ofMinutes(35));
    }

    @Test
    void l1HitDoesNotTouchRedisAgain() {
        when(mapper.selectDetail(20001L)).thenReturn(view);
        service.get(20001L);
        service.get(20001L);
        service.get(20001L);

        verify(ops, times(1)).get("product:detail:20001");
        verify(mapper, times(1)).selectDetail(20001L);
        assertThat(registry.get("cache.gets")
                        .tag("result", "hit")
                        .functionCounter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void l2HitDeserializesWithoutDb() {
        when(ops.get("product:detail:20001"))
                .thenReturn(
                        "{\"skuId\":\"20001\",\"name\":\"红心柚\",\"image\":\"\",\"price\":\"39.90\",\"onShelf\":true}");

        assertThat(service.get(20001L)).contains(view);
        verify(mapper, never()).selectDetail(20001L);
    }

    @Test
    void missingSkuIsCachedAsNullPlaceholderFor60Seconds() {
        assertThat(service.get(99999L)).isEmpty();
        verify(ops).set("product:detail:99999", "{}", Duration.ofSeconds(60));

        when(ops.get("product:detail:88888")).thenReturn("{}");
        assertThat(service.get(88888L)).isEqualTo(Optional.empty());
        verify(mapper, never()).selectDetail(88888L);
    }

    @Test
    void redisDownFallsBackToDbWithoutWriteBack() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(mapper.selectDetail(20001L)).thenReturn(view);

        assertThat(service.get(20001L)).contains(view);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisQueryTimeoutFallsBackToDb() {
        when(ops.get(anyString())).thenThrow(new QueryTimeoutException("Redis command timed out"));
        when(mapper.selectDetail(20001L)).thenReturn(view);

        assertThat(service.get(20001L)).contains(view);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisCommandErrorFallsBackToDb() {
        when(ops.get(anyString())).thenThrow(new RedisSystemException("LOADING", new RuntimeException("LOADING")));
        when(mapper.selectDetail(20001L)).thenReturn(view);

        assertThat(service.get(20001L)).contains(view);
    }

    @Test
    void writeBackTimeoutStillReturnsDbResult() {
        when(mapper.selectDetail(20001L)).thenReturn(view);
        doThrow(new QueryTimeoutException("Redis command timed out"))
                .when(ops)
                .set(anyString(), anyString(), any(Duration.class));

        assertThat(service.get(20001L)).contains(view);
    }

    @Test
    void unreadableCachedJsonIsTreatedAsMissAndOverwritten() {
        when(ops.get("product:detail:20001")).thenReturn("{\"skuId\":[1,2,3]}");
        when(mapper.selectDetail(20001L)).thenReturn(view);

        assertThat(service.get(20001L)).contains(view);
        verify(ops).set(eq("product:detail:20001"), anyString(), any(Duration.class));
    }

    @Test
    void evictLocalForcesReload() {
        when(mapper.selectDetail(20001L)).thenReturn(view);
        service.get(20001L);
        service.evictLocal(20001L);
        service.get(20001L);
        verify(ops, times(2)).get("product:detail:20001");
    }

    // ---------- 以下是演示开关（DetailCachePolicy）的各个分支：正式环境没有实现，走的都是上面的默认路径 ----------

    /** 测试用的开关：四个布尔值 + 回源次数。 */
    static final class Switches implements DetailCachePolicy {
        boolean l1 = true;
        boolean l2 = true;
        boolean singleFlight = true;
        boolean nullCache = true;
        final AtomicInteger dbLoads = new AtomicInteger();

        @Override
        public boolean l1Enabled() {
            return l1;
        }

        @Override
        public boolean l2Enabled() {
            return l2;
        }

        @Override
        public boolean singleFlightEnabled() {
            return singleFlight;
        }

        @Override
        public boolean nullCacheEnabled() {
            return nullCache;
        }

        @Override
        public <T> T aroundDbLoad(Supplier<T> load) {
            dbLoads.incrementAndGet();
            return load.get();
        }
    }

    private static final String CACHED_20001 =
            "{\"skuId\":\"20001\",\"name\":\"红心柚\",\"image\":\"\",\"price\":\"39.90\",\"onShelf\":true}";

    @Test
    void defaultPolicyKeepsEverythingOn() {
        DetailCachePolicy d = DetailCachePolicy.DEFAULT;
        assertThat(d.l1Enabled()).isTrue();
        assertThat(d.l2Enabled()).isTrue();
        assertThat(d.singleFlightEnabled()).isTrue();
        assertThat(d.nullCacheEnabled()).isTrue();
        assertThat(d.aroundDbLoad(() -> "原样执行")).isEqualTo("原样执行");
    }

    @Test
    void l1DisabledReadsRedisOnEveryRequest() {
        Switches sw = new Switches();
        sw.l1 = false;
        service.setPolicy(sw);
        when(ops.get("product:detail:20001")).thenReturn(CACHED_20001);

        assertThat(service.get(20001L)).contains(view);
        assertThat(service.get(20001L)).contains(view);
        assertThat(service.get(20001L)).contains(view);

        verify(ops, times(3)).get("product:detail:20001");
        verify(mapper, never()).selectDetail(20001L);
        assertThat(registry.get("product.detail.l2")
                        .tag("result", "hit")
                        .counter()
                        .count())
                .isEqualTo(3);
    }

    @Test
    void l2DisabledNeverTouchesRedisButStillUsesL1() {
        Switches sw = new Switches();
        sw.l2 = false;
        service.setPolicy(sw);
        when(mapper.selectDetail(20001L)).thenReturn(view);

        assertThat(service.get(20001L)).contains(view);
        assertThat(service.get(20001L)).contains(view);

        verify(ops, never()).get(anyString());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        verify(mapper, times(1)).selectDetail(20001L); // 第二次是 L1 命中
        assertThat(registry.get("product.detail.db.load").counter().count()).isEqualTo(1);
        assertThat(registry.get("product.detail.l2")
                        .tag("result", "miss")
                        .counter()
                        .count())
                .isZero(); // 没读 Redis，不算 L2 未命中
    }

    @Test
    void bothLevelsDisabledLoadsFromDbOnEveryRequest() {
        Switches sw = new Switches();
        sw.l1 = false;
        sw.l2 = false;
        service.setPolicy(sw);
        when(mapper.selectDetail(20001L)).thenReturn(view);

        service.get(20001L);
        service.get(20001L);
        service.get(20001L);

        verify(mapper, times(3)).selectDetail(20001L);
        verify(ops, never()).get(anyString());
        assertThat(sw.dbLoads).hasValue(3); // 每次回源都经过开关
    }

    @Test
    void withoutSingleFlightConcurrentMissesAllLoadThenFillL1() throws Exception {
        Switches sw = new Switches();
        sw.singleFlight = false;
        sw.l2 = false;
        service.setPolicy(sw);
        int threads = 4;
        CountDownLatch allInside = new CountDownLatch(threads);
        when(mapper.selectDetail(20001L)).thenAnswer(inv -> {
            allInside.countDown();
            // 等 4 个线程都进到回源里才返回：有单飞的话只会进来 1 个，这里会等到超时
            allInside.await(5, TimeUnit.SECONDS);
            return view;
        });

        runConcurrently(threads, () -> service.get(20001L));

        verify(mapper, times(threads)).selectDetail(20001L);
        service.get(20001L); // 已经回填进 L1，不再回源
        verify(mapper, times(threads)).selectDetail(20001L);
    }

    @Test
    void withSingleFlightConcurrentMissesLoadOnce() throws Exception {
        int threads = 4;
        CountDownLatch release = new CountDownLatch(1);
        when(mapper.selectDetail(20001L)).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return view;
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> service.get(20001L));
            }
            Thread.sleep(300); // 让 4 个线程都走到 L1：1 个在加载，其余 3 个等它
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        verify(mapper, times(1)).selectDetail(20001L);
    }

    @Test
    void nullCacheDisabledCachesMissingSkuInNeitherLevel() {
        Switches sw = new Switches();
        sw.nullCache = false;
        service.setPolicy(sw);

        assertThat(service.get(99999L)).isEmpty();
        assertThat(service.get(99999L)).isEmpty();

        verify(mapper, times(2)).selectDetail(99999L); // L1 没有缓存空结果
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class)); // Redis 也没有写空值占位
    }

    @Test
    void nullCacheDisabledWithoutSingleFlightAlsoSkipsL1() {
        Switches sw = new Switches();
        sw.nullCache = false;
        sw.singleFlight = false;
        service.setPolicy(sw);

        assertThat(service.get(99999L)).isEmpty();
        assertThat(service.get(99999L)).isEmpty();

        verify(mapper, times(2)).selectDetail(99999L);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void nullCacheDisabledStillCachesExistingSku() {
        Switches sw = new Switches();
        sw.nullCache = false;
        service.setPolicy(sw);
        when(mapper.selectDetail(20001L)).thenReturn(view);

        service.get(20001L);
        service.get(20001L);

        verify(mapper, times(1)).selectDetail(20001L);
        verify(ops).set(eq("product:detail:20001"), anyString(), any(Duration.class));
    }

    @Test
    void withoutSingleFlightMissingSkuIsStillCachedWhenNullCacheIsOn() {
        Switches sw = new Switches();
        sw.singleFlight = false;
        service.setPolicy(sw);

        assertThat(service.get(99999L)).isEmpty();
        assertThat(service.get(99999L)).isEmpty();

        verify(mapper, times(1)).selectDetail(99999L);
        verify(ops).set("product:detail:99999", "{}", Duration.ofSeconds(60));
    }

    @Test
    void aroundDbLoadWrapsOnlyRealDatabaseQueries() {
        Switches sw = new Switches();
        service.setPolicy(sw);
        when(mapper.selectDetail(20002L)).thenReturn(view);
        when(ops.get("product:detail:20001")).thenReturn(CACHED_20001);

        service.get(20002L); // 两级都没有 → 回源
        service.get(20002L); // L1 命中
        service.get(20001L); // L2 命中

        assertThat(sw.dbLoads).hasValue(1);
    }

    private static void runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(task);
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
