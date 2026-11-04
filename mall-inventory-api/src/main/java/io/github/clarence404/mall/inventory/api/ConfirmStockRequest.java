package io.github.clarence404.mall.inventory.api;

import jakarta.validation.constraints.NotBlank;

/** 确认：按订单号确认全部预占记录。 */
public record ConfirmStockRequest(@NotBlank String orderNo) {}
