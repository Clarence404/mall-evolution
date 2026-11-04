package io.github.clarence404.mall.order.query;

import io.github.clarence404.mall.order.domain.Order;
import java.time.LocalDateTime;

/** 「我的订单」列表项。 */
public record OrderListItemView(String orderNo, String status, String payAmount, LocalDateTime createdAt) {

    static OrderListItemView of(Order o) {
        return new OrderListItemView(
                o.getOrderNo(), o.getStatus().name(), OrderView.money(o.getPayAmount()), o.getCreatedAt());
    }
}
