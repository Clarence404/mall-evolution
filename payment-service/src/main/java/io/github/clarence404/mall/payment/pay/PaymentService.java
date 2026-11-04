package io.github.clarence404.mall.payment.pay;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.order.api.OrderErrorCode;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import io.github.clarence404.mall.order.api.OrderSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 发起支付：远程查订单金额与归属，一笔订单可能有多次支付尝试，每次一张支付单。 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String MOCK_CHANNEL = "mock";
    private static final int PENDING_PAYMENT = 10;

    private final OrderQueryApi orderQueryApi;
    private final PaymentMapper paymentMapper;

    public Payment create(long userId, String orderNo) {
        OrderSummaryDTO order = orderQueryApi.getByOrderNo(orderNo); // 不存在时下游返回 404，原样透传
        if (order.userId() != userId) { // 别人的订单与不存在一样返回 404
            throw new BizException(OrderErrorCode.ORDER_NOT_FOUND, "订单 " + orderNo + " 不存在");
        }
        if (order.status() != PENDING_PAYMENT) {
            throw new BizException(OrderErrorCode.ORDER_STATUS_CONFLICT, "订单 " + orderNo + " 不是待支付状态");
        }
        Payment p = new Payment();
        p.setId(Ids.next());
        p.setPaymentNo(String.valueOf(p.getId()));
        p.setOrderNo(order.orderNo());
        p.setUserId(userId);
        p.setAmount(order.payAmount());
        p.setChannel(MOCK_CHANNEL);
        paymentMapper.insert(p);
        return p;
    }
}
