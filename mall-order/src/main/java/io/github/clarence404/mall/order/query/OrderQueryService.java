package io.github.clarence404.mall.order.query;

import io.github.clarence404.mall.common.degrade.DegradeProperties;
import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.id.SnowflakeIdGenerator;
import io.github.clarence404.mall.common.web.CursorResult;
import io.github.clarence404.mall.order.api.OrderErrorCode;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.time.ZonedDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderQueryService {

    static final int MAX_PAGE_SIZE = 50;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final DegradeProperties degrade;
    private OrderListPolicy listPolicy = OrderListPolicy.DEFAULT;

    /** 只有演示环境（demo profile）注册了实现，用来复现「订单列表不加索引提示」时的样子（高性能实验 I）；正式环境一律加。 */
    @Autowired(required = false)
    void setListPolicy(OrderListPolicy listPolicy) {
        this.listPolicy = listPolicy;
    }

    /** 只看自己的订单：查到别人的订单和查不到一样返回 404，不让人借 403/404 的差别探测订单号。 */
    public Order getOwn(long userId, String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || order.getUserId() != userId) {
            throw new BizException(OrderErrorCode.ORDER_NOT_FOUND, "订单 " + orderNo + " 不存在");
        }
        order.setItems(orderItemMapper.selectByOrderId(order.getId()));
        return order;
    }

    public CursorResult<OrderListItemView> list(long userId, OrderStatus status, String cursor, int size) {
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        // 降级：订单列表只查近 N 个月时，由 N 个月前的时间算出雪花 ID 下界，仍走 idx_user_id (user_id, id)
        Long minId = degrade.getOrderListMonths() > 0
                ? SnowflakeIdGenerator.minIdAt(ZonedDateTime.now()
                        .minusMonths(degrade.getOrderListMonths())
                        .toInstant()
                        .toEpochMilli())
                : null;
        boolean plain = listPolicy.plainCursor();
        Long cursorId =
                plain ? (cursor == null || cursor.isBlank() ? null : Long.valueOf(cursor)) : OrderCursor.decode(cursor);
        List<Order> rows =
                orderMapper.listByUser(userId, status, cursorId, minId, pageSize + 1, listPolicy.indexHint());
        boolean hasMore = rows.size() > pageSize;
        List<Order> page = hasMore ? rows.subList(0, pageSize) : rows;
        long lastId = hasMore ? page.getLast().getId() : 0;
        String next = !hasMore ? null : plain ? Long.toString(lastId) : OrderCursor.encode(lastId);
        return new CursorResult<>(page.stream().map(OrderListItemView::of).toList(), next, hasMore);
    }
}
