package io.github.clarence404.mall.order.address;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.security.LoginUser;
import io.github.clarence404.mall.common.validation.OnPatch;
import io.github.clarence404.mall.order.api.OrderErrorCode;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.place.AddressForm;
import io.github.clarence404.mall.order.query.OrderQueryService;
import io.github.clarence404.mall.order.query.OrderView;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发货前改地址：AddressForm 按 OnPatch 校验，只传要改的字段；用 version 乐观锁防止后写覆盖先写。
 */
@RestController
@RequiredArgsConstructor
class OrderAddressController {

    private final OrderQueryService orderQueryService;
    private final OrderMapper orderMapper;

    @Operation(operationId = "changeOrderAddress")
    @PatchMapping("/api/orders/{orderNo}/address")
    OrderView change(
            @AuthenticationPrincipal LoginUser user,
            @PathVariable String orderNo,
            @RequestBody @Validated(OnPatch.class) AddressForm form) {
        Order order = orderQueryService.getOwn(user.id(), orderNo);
        int updated = orderMapper.updateAddress(
                order.getId(), order.getVersion(), form.receiver(), form.phone(), form.detail());
        if (updated == 0) {
            throw new BizException(OrderErrorCode.ORDER_STATUS_CONFLICT, "订单 " + orderNo + " 已发货或已被修改，请刷新后重试");
        }
        return OrderView.of(orderQueryService.getOwn(user.id(), orderNo));
    }
}
