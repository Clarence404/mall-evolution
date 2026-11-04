package io.github.clarence404.mall.order.cancel;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItem;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关单：按订单号的单笔幂等操作。状态条件更新 10→50 影响 1 行，才在同一事务里归还库存。
 *
 * <p>支付回调与超时取消同时到达时，只有一个条件更新能成功，不需要再加锁。
 */
@Service
@RequiredArgsConstructor
public class OrderCloseService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final InventoryApi inventoryApi;

    /** 返回 true 表示本次关掉了订单；用户已支付或已关闭时返回 false。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean closeIfUnpaid(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return false;
        }
        if (orderMapper.updateStatus(order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED) == 0) {
            return false;
        }
        orderItemMapper.selectByOrderId(order.getId()).stream()
                .sorted(Comparator.comparingLong(OrderItem::getSkuId)) // 与扣减同样按 sku_id 升序加锁
                .forEach(i -> inventoryApi.restore(i.getSkuId(), i.getQuantity()));
        return true;
    }
}
