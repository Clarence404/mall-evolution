package io.github.clarence404.mall.order.paid;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * order-paid 消息体：订单进入待发货后发出。积分、通知、发货单与库存确认（inventory-confirm-consumer）各自订阅，
 * 不感知支付服务的拆分。消费方各自定义反序列化类型，不依赖这个类。
 */
public record OrderPaidEvent(String orderNo, long userId, BigDecimal payAmount, LocalDateTime paidAt) {

    public static OrderPaidEvent from(PaymentSucceeded msg) {
        return new OrderPaidEvent(msg.orderNo(), msg.userId(), msg.amount(), msg.paidAt());
    }
}
