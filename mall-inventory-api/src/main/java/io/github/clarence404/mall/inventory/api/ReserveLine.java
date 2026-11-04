package io.github.clarence404.mall.inventory.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 一个 SKU 的预占 / 释放数量。 */
public record ReserveLine(@NotNull Long skuId, @Min(1) int quantity) {

    /** 由调用方的明细行构造（订单的计价明细实现了 {@link SkuQuantity}）。 */
    public static List<ReserveLine> of(List<? extends SkuQuantity> items) {
        return items.stream().map(i -> new ReserveLine(i.skuId(), i.quantity())).toList();
    }
}
