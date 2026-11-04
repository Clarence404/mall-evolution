package io.github.clarence404.mall.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 写单失败或取消订单时归还库存。 */
public record RestoreStockRequest(
        @NotBlank String orderNo, @NotEmpty List<@Valid StockLine> lines) {}
