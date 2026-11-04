package io.github.clarence404.mall.order.cancel;

import io.github.clarence404.mall.common.security.LoginUser;
import io.github.clarence404.mall.order.query.OrderView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 状态流转用动作子资源：每种流转有独立的校验和审计，客户端不能直接改 status 字段。 */
@RestController
@RequiredArgsConstructor
class CancelOrderController {

    private final OrderCancelService orderCancelService;

    @Operation(operationId = "cancelOrder")
    @ApiResponse(responseCode = "200", description = "已取消")
    @ApiResponse(
            responseCode = "409",
            description = "当前状态不能取消（ORDER_STATUS_CONFLICT）",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/api/orders/{orderNo}/cancel")
    OrderView cancel(@AuthenticationPrincipal LoginUser user, @PathVariable String orderNo) {
        return OrderView.of(orderCancelService.cancel(user.id(), orderNo));
    }
}
