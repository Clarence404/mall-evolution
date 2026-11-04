package io.github.clarence404.mall.order.paid;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.order.api.MarkPaidRequest;
import io.github.clarence404.mall.order.api.MarkPaidResult;
import io.github.clarence404.mall.order.api.OrderErrorCode;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.order.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 拆分过渡期：payment-service 处理渠道回调后同步调用 markPaid，本地事务里改订单状态并写 t_outbox（order-paid）。
 *
 * <p>渠道会重试回调，所以按订单号幂等：状态条件更新 10→20 只有第一次成功，之后返回 ALREADY_PAID。
 */
@Service
@RequiredArgsConstructor
public class OrderPaidTxService {

    private final OrderMapper orderMapper;
    private final OutboxWriter outboxWriter;

    @Transactional(rollbackFor = Exception.class)
    public MarkPaidResult markPaid(String orderNo, MarkPaidRequest req) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(OrderErrorCode.ORDER_NOT_FOUND, "订单 " + orderNo + " 不存在");
        }
        if (orderMapper.updateStatus(order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_SHIPMENT) == 1) {
            outboxWriter.append(
                    "order-paid",
                    orderNo,
                    new OrderPaidEvent(orderNo, order.getUserId(), req.amount(), req.paidAt()),
                    null);
            return MarkPaidResult.PAID;
        }
        OrderStatus current = orderMapper.selectByOrderNo(orderNo).getStatus();
        return current == OrderStatus.CANCELLED ? MarkPaidResult.ORDER_CANCELLED : MarkPaidResult.ALREADY_PAID;
    }
}
