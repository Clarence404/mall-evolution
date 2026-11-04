package io.github.clarence404.mall.payment.pay;

import io.github.clarence404.mall.payment.api.ClosePaymentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 取消订单前先关闭支付单：已有付款成功的支付单就返回 PAID，订单侧不取消、等 payment-succeeded 到达。
 */
@Service
@RequiredArgsConstructor
public class PaymentCloseService {

    private final PaymentMapper paymentMapper;

    @Transactional
    public ClosePaymentResult close(String orderNo) {
        if (paymentMapper.countPaid(orderNo) > 0) {
            return ClosePaymentResult.PAID;
        }
        paymentMapper.closePending(orderNo);
        return ClosePaymentResult.CLOSED;
    }
}
