package io.github.clarence404.mall.order.place;

import java.util.List;

/** 下单请求：只有 skuId、quantity 和收货地址，金额由服务端计算。 */
public record PlaceOrderRequest(List<OrderLine> lines, AddressForm address, String remark) {}
