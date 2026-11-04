package io.github.clarence404.mall.order.cancel;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.order.api.OrderErrorCode;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.order.query.OrderQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 用户取消：校验归属与状态，回补库存。只有待支付的订单能取消。 */
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private final OrderQueryService orderQueryService;
    private final OrderCloseService orderCloseService;

    public Order cancel(long userId, String orderNo) {
        Order order = orderQueryService.getOwn(userId, orderNo);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !orderCloseService.closeIfUnpaid(orderNo)) {
            Order latest = orderQueryService.getOwn(userId, orderNo);
            throw new BizException(OrderErrorCode.ORDER_STATUS_CONFLICT, conflictMessage(latest));
        }
        return orderQueryService.getOwn(userId, orderNo);
    }

    static String conflictMessage(Order order) {
        String state = switch (order.getStatus()) {
            case PENDING_SHIPMENT -> "已支付";
            case SHIPPED -> "已发货";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case CLOSED -> "已关闭";
            case PENDING_PAYMENT -> "状态已变化";
        };
        return "订单 " + order.getOrderNo() + " " + state + "，不能取消";
    }
}
