package io.github.clarence404.mall.order.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 支付成功改订单状态的远程契约（拆分过渡期）：payment-service 处理渠道回调后同步调用，按订单号幂等。
 *
 * <p>调用失败时 payment-service 给渠道返回失败，靠渠道的回调重试兜底；跨服务一致性一篇改为 payment-succeeded 消息后下线。
 */
@HttpExchange("/internal/orders")
public interface OrderPayApi {

    @PostExchange("/{orderNo}/paid")
    MarkPaidResult markPaid(@PathVariable String orderNo, @RequestBody MarkPaidRequest req);
}
