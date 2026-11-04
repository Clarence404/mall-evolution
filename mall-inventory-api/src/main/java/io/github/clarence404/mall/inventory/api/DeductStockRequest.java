package io.github.clarence404.mall.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 拆分过渡期的远程扣减：带上订单号，便于日志与每日核对脚本对账。 */
public record DeductStockRequest(
        @NotBlank String orderNo, @NotEmpty List<@Valid StockLine> lines) {}
