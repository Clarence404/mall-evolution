package io.github.clarence404.mall.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.clarence404.mall.product.admin.ProductService;
import io.github.clarence404.mall.product.detail.ProductDetailService;
import io.github.clarence404.mall.product.detail.ProductDetailView;
import io.github.clarence404.mall.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 两级缓存 + Cache Aside：提交后删 L2、广播删 L1，改价后很快读到新价格。需要 Docker。 */
class ProductDetailCacheIT extends IntegrationTestBase {

    @Autowired
    ProductDetailService detailService;

    @Autowired
    ProductService productService;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void readFillsL2AndUpdateInvalidatesBothLevels() {
        ProductDetailView before = detailService.get(20002L).orElseThrow();
        assertThat(redis.hasKey("product:detail:20002")).isTrue();
        assertThat(redis.getExpire("product:detail:20002")).isBetween(30L * 60 - 5, 35L * 60);

        productService.update(20002L, null, new BigDecimal("13.80"), null);

        assertThat(redis.hasKey("product:detail:20002")).isFalse();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(detailService.get(20002L).orElseThrow().price())
                                .isEqualTo("13.80"));
        assertThat(before.price()).isEqualTo("12.80");
    }

    @Test
    void missingSkuIsCachedAsNullPlaceholder() {
        assertThat(detailService.get(99_999L)).isEmpty();
        assertThat(redis.opsForValue().get("product:detail:99999")).isEqualTo("{}");
        assertThat(redis.getExpire("product:detail:99999")).isLessThanOrEqualTo(60L);
    }
}
