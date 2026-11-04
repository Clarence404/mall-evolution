package io.github.clarence404.mall.product.detail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class ProductCacheInvalidatorTest {

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ProductCacheInvalidator invalidator = new ProductCacheInvalidator(redis);

    @Test
    void deletesL2AndBroadcastsTwiceWithOneSecondDelay() {
        invalidator.onChanged(new ProductChangedEvent(20001L));

        verify(redis, times(1)).delete("product:detail:20001");
        verify(redis, times(1)).convertAndSend("cache:invalidate:product", "20001");
        // 延迟双删：1 秒后再删一次
        verify(redis, timeout(3000).times(2)).delete("product:detail:20001");
        verify(redis, timeout(3000).times(2)).convertAndSend("cache:invalidate:product", "20001");
    }

    @Test
    void redisFailureIsSwallowedAndLeftToTtl() {
        when(redis.delete("product:detail:1")).thenThrow(new IllegalStateException("down"));
        invalidator.evictNow(1L); // 不抛异常
    }
}
