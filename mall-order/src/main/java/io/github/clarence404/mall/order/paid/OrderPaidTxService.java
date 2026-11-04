package io.github.clarence404.mall.order.paid;

import static io.github.clarence404.mall.order.domain.OrderStatus.CANCELLED;
import static io.github.clarence404.mall.order.domain.OrderStatus.PENDING_PAYMENT;
import static io.github.clarence404.mall.order.domain.OrderStatus.PENDING_SHIPMENT;

import io.github.clarence404.mall.common.mq.ConsumeRecordMapper;
import io.github.clarence404.mall.common.outbox.OutboxWriter;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * order-payment-consumer：去重表加条件更新。原 pay 包已随支付迁出。
 *
 * <p>按支付单号去重（一个订单可能有多张支付单）；去重记录与状态变更在同一个事务里，订单状态 WHERE status = 10 的条件更新
 * 再兜一层，即使去重记录被清理，重复消息也只影响 0 行，不会重复写 order-paid。
 *
 * <p>条件更新没成功时按订单当前状态处理：已取消（关单后才到的支付）或已被另一张支付单付过（重复付款），都写
 * order-refund-required 退掉这一笔；同一张支付单的重复消息直接确认。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaidTxService {

    static final String GROUP = "order-payment-consumer";

    private final ConsumeRecordMapper consumeRecordMapper;
    private final OrderMapper orderMapper;
    private final OutboxWriter outboxWriter;

    @Transactional(rollbackFor = Exception.class)
    public void markPaid(PaymentSucceeded msg) {
        if (consumeRecordMapper.insertIgnore(GROUP, msg.paymentNo()) == 0) {
            return; // t_consume_record 已有：重复消息
        }
        if (orderMapper.transit(msg.orderNo(), PENDING_PAYMENT, PENDING_SHIPMENT, msg.paidAt()) == 1) {
            orderMapper.updatePaymentNo(msg.orderNo(), msg.paymentNo());
            outboxWriter.append("order-paid", msg.orderNo(), OrderPaidEvent.from(msg), null);
            return;
        }
        OrderStatus status = orderMapper.selectStatus(msg.orderNo());
        if (status == CANCELLED) { // 关单后才到的支付：原路退款
            requireRefund(msg);
            return;
        }
        if (status != null && status != PENDING_PAYMENT) {
            String paidBy = orderMapper.selectPaymentNo(msg.orderNo());
            if (paidBy != null && !paidBy.equals(msg.paymentNo())) { // 订单已被另一张支付单付过：退掉这笔重复付款
                log.warn("订单重复付款，退掉后到的一笔，orderNo={}，已入账 {}，退款 {}", msg.orderNo(), paidBy, msg.paymentNo());
                requireRefund(msg);
            }
            // 同一张支付单的重复消息（去重记录已被清理）：直接确认
        }
    }

    /** 消息键是支付单号：同一订单的两笔退款（关单后到账 + 重复付款）互不冲突。 */
    private void requireRefund(PaymentSucceeded msg) {
        outboxWriter.append("order-refund-required", msg.paymentNo(), msg, null);
    }
}
