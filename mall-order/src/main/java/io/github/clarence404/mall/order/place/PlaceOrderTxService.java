package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.InventoryErrorCode;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 下单事务：扣库存 + 写订单 + 写明细。事务方法放在单独的 Bean 里，由 {@link PlaceOrderService} 调用。
 *
 * <p>不在事务内部 catch 唯一键冲突：撞唯一键时库存已经扣了，必须让异常抛出、整体回滚。
 */
@Service
@RequiredArgsConstructor
public class PlaceOrderTxService {

    private final InventoryApi inventoryApi;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    @Transactional(rollbackFor = Exception.class)
    public Order create(long userId, String bizNo, PlaceOrderRequest req, List<PricedItem> items) {
        items.stream()
                .sorted(Comparator.comparing(PricedItem::skuId)) // 按 sku_id 升序扣减，避免死锁
                .forEach(i -> {
                    if (!inventoryApi.deduct(i.skuId(), i.quantity())) {
                        throw new BizException(InventoryErrorCode.STOCK_INSUFFICIENT);
                    }
                });
        Order order = Order.create(userId, bizNo, req, items); // 生成雪花 ID 与订单号，状态 10
        orderMapper.insert(order); // 撞 uk_user_biz 抛 DuplicateKeyException
        orderItemMapper.batchInsert(order.items());
        return order;
    }
}
