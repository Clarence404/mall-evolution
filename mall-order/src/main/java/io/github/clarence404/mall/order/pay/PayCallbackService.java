package io.github.clarence404.mall.order.pay;

import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.order.notify.PaidNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 支付成功回调：t_payment 置成功、t_order 10→20 在一个本地事务里。
 *
 * <p>阶段一：短信通知在事务提交后同步调用，第三方一慢就拖慢回调，阶段二「异步化」改为本地消息表。
 */
@Service
@RequiredArgsConstructor
public class PayCallbackService {

    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final PaidNotifier paidNotifier;

    @Transactional
    public void onPaid(PayNotify n) {
        if (paymentMapper.markSuccess(n.paymentNo(), n.channelTradeNo()) == 0) {
            return; // 重复回调：支付单已是成功状态
        }
        if (orderMapper.updateStatus(n.orderId(), OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_SHIPMENT) == 0) {
            paymentMapper.markRefundPending(n.paymentNo()); // 订单已超时关闭：支付单转待退款，走原路退款
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { // 调用第三方不进事务，放到提交之后
                paidNotifier.notifyPaid(n);
            }
        });
    }
}
