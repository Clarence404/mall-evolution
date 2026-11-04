package io.github.clarence404.mall.payment.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 支付服务的远程契约（集群内）：取消订单前先关闭支付单。按订单号幂等。
 */
@HttpExchange("/internal/payments")
public interface PaymentApi {

    /** CLOSED：待支付的支付单已全部关闭，可以取消订单；PAID：用户已经付款，不取消，等 payment-succeeded 到达。 */
    @PostExchange("/{orderNo}/close")
    ClosePaymentResult close(@PathVariable String orderNo);
}
