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

    /** skuId 先做格式校验（正整数），不存在的 SKU 缓存空值 60 秒，防穿透。 */
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
