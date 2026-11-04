package io.github.clarence404.mall.payment.pay;

import io.github.clarence404.mall.common.mq.ConsumeRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * order-refund-required：关闭支付单与用户付款的竞态（每天个位数），以及同一订单付了两次款，支付服务把消息里那一张
 * 支付单原路退款并通知用户。按支付单号去重，去重记录与支付单状态变更在同一个本地事务里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    static final String GROUP = "payment-refund-consumer";

    private final ConsumeRecordMapper consumeRecordMapper;
    private final PaymentMapper paymentMapper;

    @Transactional
    public void refund(String orderNo, String paymentNo) {
        if (consumeRecordMapper.insertIgnore(GROUP, paymentNo) == 0) {
            return;
        }
        int n = paymentMapper.markRefundPending(paymentNo);
        // 演示用模拟渠道：真实实现在这里调用渠道的退款接口，渠道退款回调后再置为已退款（4）并通知用户
        log.info("发起原路退款，orderNo={}，paymentNo={}，影响 {} 笔", orderNo, paymentNo, n);
    }
}
