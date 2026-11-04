package io.github.clarence404.mall.product.admin;

import io.github.clarence404.mall.product.detail.ProductCacheInvalidator;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台接口：/api/admin/** 要求访问令牌带 ADMIN 角色（见 SecurityConfig），普通用户返回 403。
 */
@RestController
@RequiredArgsConstructor
class ProductAdminController {

    private final ProductService productService;
    private final ProductCacheInvalidator cacheInvalidator;

    @Operation(operationId = "adminUpdateProduct")
    @PutMapping("/api/admin/products/{skuId}")
    ResponseEntity<Void> update(@PathVariable long skuId, @RequestBody @Valid UpdateProductRequest req) {
        productService.update(skuId, req.name(), req.price(), req.status());
        return ResponseEntity.noContent().build();
    }

    /** 价格未生效时手动刷新缓存。 */
    @Operation(operationId = "adminEvictProductCache")
    @PostMapping("/api/admin/products/{skuId}/cache-evict")
    ResponseEntity<Void> evict(@PathVariable long skuId) {
        cacheInvalidator.evictNow(skuId);
        return ResponseEntity.noContent().build();
    }

    record UpdateProductRequest(
            @Size(max = 128) String name,

            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2)
            BigDecimal price,

            @Min(0) @Max(1) Integer status) {}
}
