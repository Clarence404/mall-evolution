package io.github.clarence404.mall.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 预占：订单号先生成，作为预占的幂等键。
 *
 * @param payTimeoutMinutes 本单的支付超时（阶段四新增的可选字段，兼容变更）：活动 SKU 为 5 分钟，预占 expire_at 随之变为
 *     下单后 20 分钟；为空按 15 分钟、expire_at 30 分钟处理
 */
public record ReserveStockRequest(
        @NotBlank String orderNo, @NotEmpty List<@Valid ReserveLine> lines, Integer payTimeoutMinutes) {

    public ReserveStockRequest(String orderNo, List<ReserveLine> lines) {
        this(orderNo, lines, null);
    }
}
