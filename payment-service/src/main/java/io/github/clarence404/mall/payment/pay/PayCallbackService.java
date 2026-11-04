package io.github.clarence404.mall.payment.pay;

import io.github.clarence404.mall.order.api.MarkPaidRequest;
import io.github.clarence404.mall.order.api.MarkPaidResult;
import io.github.clarence404.mall.order.api.OrderPayApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 支付成功回调（拆分过渡期）：先把 t_payment 置成功，再同步调用 OrderPayApi.markPaid。
 *
 * <p>调用失败（超时、5xx）直接抛出，给渠道返回失败，靠渠道的回调重试兜底；所以重复回调时即使支付单已是成功状态，
 * 也要再调一次 markPaid（订单侧按订单号幂等）。写接口不自动重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayCallbackService {

    private final PaymentMapper paymentMapper;
    private final OrderPayApi orderPayApi;

    public void onPaid(PayNotify n) {
        if (paymentMapper.markSuccess(n.paymentNo(), n.channelTradeNo()) == 0) {
            log.info("重复回调，支付单已是成功状态，继续确认订单，paymentNo={}", n.paymentNo());
        }
        MarkPaidResult r =
                orderPayApi.markPaid(n.orderNo(), new MarkPaidRequest(n.paymentNo(), n.amount(), n.paidAt()));
        if (r == MarkPaidResult.ORDER_CANCELLED) {
            paymentMapper.markRefundPending(n.paymentNo()); // 订单已超时关闭：支付单转待退款，走原路退款
        }
    }
}
