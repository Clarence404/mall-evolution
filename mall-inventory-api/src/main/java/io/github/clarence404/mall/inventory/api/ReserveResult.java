package io.github.clarence404.mall.inventory.api;

/** 预占结果：OK（含重复请求）；REJECTED 表示该订单号已被 release 的空回滚占位挡住（悬挂）。库存不足走 409。 */
public enum ReserveResult {
    OK,
    REJECTED
}
