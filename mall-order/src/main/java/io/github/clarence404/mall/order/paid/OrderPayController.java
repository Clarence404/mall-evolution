package io.github.clarence404.mall.order.paid;

import io.github.clarence404.mall.order.api.MarkPaidRequest;
import io.github.clarence404.mall.order.api.MarkPaidResult;
import io.github.clarence404.mall.order.api.OrderPayApi;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/** 集群内接口（/internal/orders/{orderNo}/paid），路径与参数注解从 OrderPayApi 继承。 */
@RestController
@RequiredArgsConstructor
class OrderPayController implements OrderPayApi {

    private final OrderPaidTxService orderPaidTxService;

    @Override
    public MarkPaidResult markPaid(String orderNo, MarkPaidRequest req) {
        return orderPaidTxService.markPaid(orderNo, req);
    }
}
