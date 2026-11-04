package io.github.clarence404.mall.order.paid;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * order-paid 消息体：订单进入待发货后发出，points-consumer、notify-consumer、shipment-consumer 各收一份；
 * 积分、通知的消费者不感知支付服务的拆分。消费方各自定义反序列化类型，不依赖这个类。
 */
public record OrderPaidEvent(String orderNo, long userId, BigDecimal payAmount, LocalDateTime paidAt) {}
