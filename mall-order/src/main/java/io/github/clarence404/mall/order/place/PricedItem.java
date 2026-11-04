package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.inventory.api.SkuQuantity;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 事务外算好价格的明细行：金额统一 BigDecimal，HALF_UP 保留 2 位。 */
public record PricedItem(long skuId, String productName, String productImage, BigDecimal unitPrice, int quantity)
        implements SkuQuantity {

    public BigDecimal itemAmount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }
}
