package io.github.clarence404.mall.order.pay;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.order.api.OrderErrorCode;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.order.query.OrderQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 发起支付：一笔订单可能有多次支付尝试，每次一张支付单。 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final String MOCK_CHANNEL = "mock";

    private final OrderQueryService orderQueryService;
    private final PaymentMapper paymentMapper;

    public Payment create(long userId, String orderNo) {
        Order order = orderQueryService.getOwn(userId, orderNo);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new BizException(OrderErrorCode.ORDER_STATUS_CONFLICT, "订单 " + orderNo + " 不是待支付状态");
        }
        Payment p = new Payment();
        p.setId(Ids.next());
        p.setPaymentNo(String.valueOf(p.getId()));
        p.setOrderId(order.getId());
        p.setOrderNo(order.getOrderNo());
        p.setUserId(userId);
        p.setAmount(order.getPayAmount());
        p.setChannel(MOCK_CHANNEL);
        paymentMapper.insert(p);
        return p;
    }
}
