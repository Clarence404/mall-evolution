package io.github.clarence404.mall.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 释放：带上明细，空回滚时要按 SKU 写占位。 */
public record ReleaseStockRequest(
        @NotBlank String orderNo, @NotEmpty List<@Valid ReserveLine> lines) {}
