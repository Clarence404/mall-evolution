package io.github.clarence404.mall.order.query;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.order.api.OrderErrorCode;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import io.github.clarence404.mall.order.api.OrderSummaryDTO;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/** 集群内的订单查询（/internal/orders/{orderNo}），供支付服务等调用。 */
@RestController
@RequiredArgsConstructor
class InternalOrderController implements OrderQueryApi {

    private final OrderMapper orderMapper;

    @Override
    public OrderSummaryDTO getByOrderNo(String orderNo) {
        Order o = orderMapper.selectByOrderNo(orderNo);
        if (o == null) {
            throw new BizException(OrderErrorCode.ORDER_NOT_FOUND, "订单 " + orderNo + " 不存在");
        }
        return new OrderSummaryDTO(o.getOrderNo(), o.getUserId(), o.getStatus().getCode(), o.getPayAmount());
    }
}
