package io.github.clarence404.mall.order.place;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 下单请求：只有 skuId、quantity 和收货地址，金额由服务端计算，客户端传来的金额一律不信。 */
public record PlaceOrderRequest(
        @NotEmpty @Size(max = 50) List<@Valid OrderLine> lines,
        @NotNull @Valid AddressForm address,
        @Size(max = 200) String remark) {}
