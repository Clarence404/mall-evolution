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
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderQueryService {

    static final int MAX_PAGE_SIZE = 50;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final DegradeProperties degrade;

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
        List<Order> rows = orderMapper.listByUser(userId, status, OrderCursor.decode(cursor), minId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Order> page = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore ? OrderCursor.encode(page.getLast().getId()) : null;
        return new CursorResult<>(page.stream().map(OrderListItemView::of).toList(), next, hasMore);
    }
}
