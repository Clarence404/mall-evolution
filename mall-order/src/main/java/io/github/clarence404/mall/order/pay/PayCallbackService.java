package io.github.clarence404.mall.order.pay;

import io.github.clarence404.mall.order.api.OrderPaidEvent;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.order.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付成功回调只剩一个本地事务：t_payment 置成功、t_order 10→20、写 t_outbox（order-paid）。
 *
 * <p>积分、短信与推送、建发货单都由各自的消费组异步完成，下游再慢也只是自己的消费组变慢。
 */
@Service
@RequiredArgsConstructor
public class PayCallbackService {

    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final OutboxWriter outboxWriter;

    @Transactional
    public void onPaid(PayNotify n) {
        if (paymentMapper.markSuccess(n.paymentNo(), n.channelTradeNo()) == 0) {
            return; // 重复回调：支付单已是成功状态
        }
        if (orderMapper.updateStatus(n.orderId(), OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_SHIPMENT) == 0) {
            paymentMapper.markRefundPending(n.paymentNo()); // 订单已超时关闭：支付单转待退款，走原路退款
            return;
        }
        outboxWriter.append("order-paid", n.orderNo(), OrderPaidEvent.of(n), null);
    }
}
