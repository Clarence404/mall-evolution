package io.github.clarence404.mall.product.detail;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.product.api.ProductErrorCode;
import io.github.clarence404.mall.product.stock.StockDisplayService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class ProductDetailController {

    private final ProductDetailService productDetailService;
    private final StockDisplayService stockDisplayService;

    /**
     * skuId 先做格式校验（正整数），不存在的 SKU 缓存空值 60 秒，防穿透。
     *
     * <p>两部分数据都先读本机缓存：商品信息是 L1 Caffeine + L2 Redis，「有货」提示是本机 2 秒 + Redis 10 秒 + 库存服务
     * （熔断、舱壁、失败按有货兜底）。Redis 或库存服务出问题时接口照常返回 200。
     */
    @Operation(operationId = "getProductDetail")
    @GetMapping("/api/products/{skuId}")
    ProductDetailPage detail(@PathVariable @Positive long skuId) {
        ProductDetailView product = productDetailService
                .get(skuId)
                .orElseThrow(() -> new BizException(ProductErrorCode.PRODUCT_NOT_FOUND, "商品 " + skuId + " 不存在"));
        return new ProductDetailPage(product, stockDisplayService.inStock(skuId));
    }

    record ProductDetailPage(ProductDetailView product, boolean inStock) {}
}
