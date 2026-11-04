package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.common.outbox.OutboxWriter;
import io.github.clarence404.mall.order.cancel.OrderTimeoutMessage;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 下单事务（阶段三）：库存已拆成独立服务，本地事务只包得住订单库：写 t_order、t_order_item 和 order-timeout 定时消息。
 *
 * <p>不在事务内部 catch 唯一键冲突：让异常抛出、整体回滚，冲突由 {@link PlaceOrderService} 在事务外处理。
 */
@Service
public class PlaceOrderTxService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OutboxWriter outboxWriter;
    private final PromoProperties promo;

    public PlaceOrderTxService(
            OrderMapper orderMapper,
            OrderItemMapper orderItemMapper,
            OutboxWriter outboxWriter,
            PromoProperties promo) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.outboxWriter = outboxWriter;
        this.promo = promo;
    }

    @Transactional(rollbackFor = Exception.class)
    public Order create(long userId, String orderNo, String bizNo, PlaceOrderRequest req, List<PricedItem> items) {
        Order order = Order.create(userId, orderNo, bizNo, req, items); // 雪花 ID 主键，状态 10
        orderMapper.insert(order); // 撞 uk_user_biz 抛 DuplicateKeyException
        orderItemMapper.batchInsert(order.items());
        // 订单里只要有一个活动 SKU，超时关单的定时消息就按 5 分钟投递（否则 15 分钟）
        Duration payTimeout =
                promo.payTimeoutFor(items.stream().map(PricedItem::skuId).toList());
        outboxWriter.append(
                "order-timeout",
                orderNo,
                new OrderTimeoutMessage(orderNo),
                order.getCreatedAt().plus(payTimeout));
        return order;
    }
}
