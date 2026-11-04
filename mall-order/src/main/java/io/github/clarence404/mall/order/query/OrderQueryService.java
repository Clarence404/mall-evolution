package io.github.clarence404.mall.order.query;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.web.CursorResult;
import io.github.clarence404.mall.order.api.OrderErrorCode;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import io.github.clarence404.mall.order.api.OrderSummaryDTO;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderQueryService implements OrderQueryApi {

    static final int MAX_PAGE_SIZE = 50;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    @Override
    public Optional<OrderSummaryDTO> findByOrderNo(String orderNo) {
        return Optional.ofNullable(orderMapper.selectByOrderNo(orderNo))
                .map(o -> new OrderSummaryDTO(
                        o.getOrderNo(), o.getUserId(), o.getStatus().getCode(), o.getPayAmount()));
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
        List<Order> rows = orderMapper.listByUser(userId, status, OrderCursor.decode(cursor), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Order> page = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore ? OrderCursor.encode(page.getLast().getId()) : null;
        return new CursorResult<>(page.stream().map(OrderListItemView::of).toList(), next, hasMore);
    }
}
