package io.github.clarence404.mall.inventory.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 一个 SKU 的扣减 / 归还数量。 */
public record StockLine(@NotNull Long skuId, @Min(1) int quantity) {}
