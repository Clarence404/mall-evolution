package io.github.clarence404.mall.product.api;

import java.math.BigDecimal;

/**
 * 商品对外视图：阶段一不拆 SPU / SKU，一行即一个可售规格，id 即 sku_id。
 */
public record ProductDTO(long id, String name, String image, BigDecimal price, boolean onShelf) {}
