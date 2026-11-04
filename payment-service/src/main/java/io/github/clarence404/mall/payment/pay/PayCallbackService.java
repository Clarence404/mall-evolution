package io.github.clarence404.mall.payment.pay;

import io.github.clarence404.mall.common.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付成功回调：验签后在一个本地事务里把 t_payment 改为成功并写入自己库里的 t_outbox，由投递任务发出
 * payment-succeeded；同步的 OrderPayApi.markPaid 就此下线。支付成功后不能失败、只能往前推。
 *
 * <p>消息键用支付单号而不是订单号：一个订单可以有多张支付单（用户换渠道重新发起），两张都付成功时第二条
 * payment-succeeded 不能撞 uk_topic_key 回滚整个回调，订单侧收到后发现订单已支付，会要求退掉这笔重复付款。
 */
@Service
@RequiredArgsConstructor
public class PayCallbackService {

    private final PaymentMapper paymentMapper;
    private final OutboxWriter outboxWriter;

    @Transactional
    public void onPaid(PayNotify n) {
        if (paymentMapper.markSuccess(n.paymentNo(), n.channelTradeNo()) == 0) {
            return; // 重复回调：支付单已是成功状态，payment-succeeded 已写过
        }
        outboxWriter.append(
                "payment-succeeded",
                n.paymentNo(),
                new PaymentSucceeded(n.orderNo(), n.paymentNo(), n.userId(), n.amount(), n.paidAt()),
                null);
    }
}
