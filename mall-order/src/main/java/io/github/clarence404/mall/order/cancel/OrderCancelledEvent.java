package io.github.clarence404.mall.order.cancel;

import io.github.clarence404.mall.inventory.api.ReserveLine;
import java.util.List;

/** order-cancelled 消息体：inventory-release-consumer 收到后执行 release。 */
public record OrderCancelledEvent(String orderNo, List<ReserveLine> lines) {}
