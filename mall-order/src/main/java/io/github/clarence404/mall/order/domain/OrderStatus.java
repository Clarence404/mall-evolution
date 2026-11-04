package io.github.clarence404.mall.order.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态：TINYINT 存储，取值留间隔，方便以后插入中间状态。流转一律用带旧状态的条件更新。
 */
public enum OrderStatus {
    PENDING_PAYMENT(10),
    PENDING_SHIPMENT(20),
    SHIPPED(30),
    COMPLETED(40),
    CANCELLED(50),
    CLOSED(60);

    /** 允许的流转，其余一律拒绝。 */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            PENDING_PAYMENT, Set.of(PENDING_SHIPMENT, CANCELLED),
            PENDING_SHIPMENT, Set.of(SHIPPED, CLOSED),
            SHIPPED, Set.of(COMPLETED));

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public boolean canTransitTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** 非法流转直接抛：调用方写错了状态机，而不是并发冲突（并发冲突靠条件更新影响 0 行发现）。 */
    public void checkTransitTo(OrderStatus target) {
        if (!canTransitTo(target)) {
            throw new IllegalStateException("订单状态不允许从 " + this + " 流转到 " + target);
        }
    }

    public static OrderStatus fromCode(int code) {
        return Arrays.stream(values())
                .filter(s -> s.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知订单状态: " + code));
    }
}
