package io.github.clarence404.mall.product.detail;

import io.github.clarence404.mall.product.domain.ProductMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动预热（大促 T-1 天预案的一部分）：把头部商品加载进 L1，之后 Spring Boot 才把就绪状态切到 ACCEPTING_TRAFFIC，
 * readiness 探针在预热之后才通过，新扩出来的 Pod 不会在 0 点集中回源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ProductWarmupRunner implements ApplicationRunner {

    private final ProductMapper productMapper;
    private final ProductDetailService productDetailService;

    @Value("${mall.warmup.top-products:500}")
    private int topProducts;

    @Override
    public void run(ApplicationArguments args) {
        if (topProducts <= 0) {
            return;
        }
        List<Long> skuIds = productMapper.selectHotSkuIds(topProducts);
        skuIds.forEach(productDetailService::get);
        log.info("预热完成：加载 {} 个头部商品进 L1", skuIds.size());
    }
}
