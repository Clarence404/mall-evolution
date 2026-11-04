package io.github.clarence404.mall.order.api;

import java.util.Optional;

/**
 * 订单模块对外的查询接口，其他模块只能用 api 包。
 */
public interface OrderQueryApi {

    Optional<OrderSummaryDTO> findByOrderNo(String orderNo);
}
