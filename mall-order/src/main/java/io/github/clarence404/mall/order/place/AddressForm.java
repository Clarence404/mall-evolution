package io.github.clarence404.mall.order.place;

/** 收货地址：下单时平铺快照到 t_order，用户之后改地址簿不影响已下的单。 */
public record AddressForm(String receiver, String phone, String detail) {}
