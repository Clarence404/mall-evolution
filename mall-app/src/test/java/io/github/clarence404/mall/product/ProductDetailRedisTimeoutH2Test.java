package io.github.clarence404.mall.product;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.support.H2TestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Redis 命令超时（不是连不上）时商品详情照样回源，接口返回 200 而不是 500。 */
class ProductDetailRedisTimeoutH2Test extends H2TestBase {

    @MockitoBean
    StringRedisTemplate redis;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void redisTimesOut() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new QueryTimeoutException("Redis command timed out"));
    }

    @Test
    void redisQueryTimeoutFallsBackToDbAndReturns200() throws Exception {
        mvc.perform(get("/api/products/20003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.skuId").value("20003"));
    }
}
