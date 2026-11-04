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
}
