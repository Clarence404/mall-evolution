package io.github.clarence404.mall.product.recommend;

import io.github.clarence404.mall.common.degrade.DegradeProperties;
import io.github.clarence404.mall.product.detail.ProductDetailService;
import io.github.clarence404.mall.product.detail.ProductDetailView;
import io.github.clarence404.mall.product.domain.ProductMapper;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品详情页的「猜你喜欢」。降级开关 mall.degrade.recommend-enabled 关闭后，返回运营配置的固定列表，不再查库。
 */
@RestController
@RequiredArgsConstructor
class RecommendController {

    private final DegradeProperties degrade;
    private final ProductMapper productMapper;
    private final ProductDetailService productDetailService;

    @Value("${mall.recommend.fallback-sku-ids:}")
    private List<Long> fallbackSkuIds;

    @Operation(operationId = "listRecommendations")
    @GetMapping("/api/products/{skuId}/recommendations")
    List<ProductDetailView> recommend(@PathVariable long skuId) {
        List<Long> ids = degrade.isRecommendEnabled() ? productMapper.selectSimilarSkuIds(skuId, 4) : fallbackSkuIds;
        return ids.stream()
                .map(productDetailService::get) // 走两级缓存
                .flatMap(Optional::stream)
                .toList();
    }
}
