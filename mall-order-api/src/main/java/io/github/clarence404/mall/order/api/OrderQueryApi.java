package io.github.clarence404.mall.order.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * 订单查询的远程契约（集群内）：支付服务创建支付单时查订单金额与归属。订单不存在时返回 404 ORDER_NOT_FOUND。
 */
@HttpExchange("/internal/orders")
public interface OrderQueryApi {

    @GetExchange("/{orderNo}")
    OrderSummaryDTO getByOrderNo(@PathVariable String orderNo);
}
