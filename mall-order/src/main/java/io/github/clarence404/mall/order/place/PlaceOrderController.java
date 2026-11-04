package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.common.security.LoginUser;
import io.github.clarence404.mall.common.validation.OnCreate;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.query.OrderQueryService;
import io.github.clarence404.mall.order.query.OrderView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PlaceOrderController {

    private final PlaceOrderService placeOrderService;
    private final OrderQueryService orderQueryService;

    /** 下单：同一个 Idempotency-Key 的重复请求重放首次结果，同样返回 201，并带 {@code Idempotent-Replayed: true}。 */
    @Operation(operationId = "placeOrder")
    @PostMapping("/api/orders")
    public ResponseEntity<OrderView> place(
            @AuthenticationPrincipal LoginUser user,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idemKey,
            @RequestBody @Validated(OnCreate.class) PlaceOrderRequest req) {
        PlaceOrderResult r = placeOrderService.place(user.id(), idemKey, req);
        // 重放时补上明细，保证与首次响应一致
        Order order =
                r.replayed() ? orderQueryService.getOwn(user.id(), r.order().getOrderNo()) : r.order();
        OrderView view = OrderView.of(order); // 对外只给 orderNo，不给主键 id
        return ResponseEntity.created(URI.create("/api/orders/" + view.orderNo()))
                .header("Idempotent-Replayed", String.valueOf(r.replayed()))
                .body(view);
    }
}
