package io.github.clarence404.mall.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 预占：订单号先生成，作为预占的幂等键。 */
public record ReserveStockRequest(
        @NotBlank String orderNo, @NotEmpty List<@Valid ReserveLine> lines) {}
