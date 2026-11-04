package io.github.clarence404.mall.order.api;

import io.github.clarence404.mall.order.pay.PayNotify;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * order-paid 消息体：支付成功、订单进入待发货后发出，points-consumer、notify-consumer、shipment-consumer 各收一份。
 *
 * <p>消息契约按 JSON 字段约定，消费方各自定义自己的反序列化类型，不依赖这个类。
 */
public record OrderPaidEvent(String orderNo, long userId, BigDecimal payAmount, LocalDateTime paidAt) {

    public static OrderPaidEvent of(PayNotify n) {
        return new OrderPaidEvent(n.orderNo(), n.userId(), n.amount(), n.paidAt());
    }
}
