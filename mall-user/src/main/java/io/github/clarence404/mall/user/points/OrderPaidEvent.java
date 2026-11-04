package io.github.clarence404.mall.user.points;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * points-consumer 自己的 order-paid 消息类型：按 JSON 字段约定反序列化。mall-user 不能依赖 mall-order，所以不引用生产方的类。
 */
public record OrderPaidEvent(String orderNo, long userId, BigDecimal payAmount, LocalDateTime paidAt) {}
