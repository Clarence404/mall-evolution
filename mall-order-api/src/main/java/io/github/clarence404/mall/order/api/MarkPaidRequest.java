package io.github.clarence404.mall.order.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MarkPaidRequest(String paymentNo, BigDecimal amount, LocalDateTime paidAt) {}
