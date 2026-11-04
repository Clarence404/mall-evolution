package io.github.clarence404.mall.inventory.api;

/** 带 SKU 和数量的明细行，供 {@link ReserveLine#of} 转换。 */
public interface SkuQuantity {

    long skuId();

    int quantity();
}
