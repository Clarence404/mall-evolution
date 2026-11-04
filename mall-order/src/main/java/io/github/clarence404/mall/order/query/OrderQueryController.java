package io.github.clarence404.mall.order.query;

import io.github.clarence404.mall.common.security.LoginUser;
import io.github.clarence404.mall.common.web.CursorResult;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.payment.api.PaymentStatusView;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class OrderQueryController {

    private final OrderQueryService orderQueryService;
    private final PaymentQueryClient paymentQueryClient;

    @Operation(operationId = "getOrder")
    @GetMapping("/api/orders/{orderNo}")
    OrderView detail(@AuthenticationPrincipal LoginUser user, @PathVariable String orderNo) {
        return OrderView.of(orderQueryService.getOwn(user.id(), orderNo));
    }

    /** 用户 ID 从登录态取，不接受参数传入。 */
    @Operation(operationId = "listMyOrders")
    @GetMapping("/api/orders")
    CursorResult<OrderListItemView> list(
            @AuthenticationPrincipal LoginUser user,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return orderQueryService.list(user.id(), status, cursor, size);
    }

    /** 「支付成功，订单确认中」时 App 轮询的支付状态（阶段四新增，兼容变更）。 */
    @Operation(operationId = "getOrderPayment")
    @GetMapping("/api/orders/{orderNo}/payment")
    PaymentStatusView payment(@AuthenticationPrincipal LoginUser user, @PathVariable String orderNo) {
        orderQueryService.getOwn(user.id(), orderNo); // 只能看自己的订单
        return paymentQueryClient.query(orderNo);
    }
}
