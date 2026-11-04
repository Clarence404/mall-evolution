package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.order.domain.Order;

/** 下单结果：replayed = true 表示命中幂等键，返回的是第一次下的那笔订单。 */
public record PlaceOrderResult(Order order, boolean replayed) {

    public static PlaceOrderResult created(Order order) {
        return new PlaceOrderResult(order, false);
    }

    public static PlaceOrderResult replayed(Order order) {
        return new PlaceOrderResult(order, true);
    }
}
