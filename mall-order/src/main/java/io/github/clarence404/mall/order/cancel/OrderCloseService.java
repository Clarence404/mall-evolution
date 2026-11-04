package io.github.clarence404.mall.order.cancel;

import io.github.clarence404.mall.inventory.api.StockLine;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 关单（拆分过渡期）：本地事务里做状态条件更新 10→50，并在同一个事务里写一条归还库存任务；提交后远程归还。
 *
 * <p>归还失败不丢：任务留在 t_stock_restore_task，由 {@link StockRestorer} 定时重试，库存服务按 order_no 去重。
 * 下一篇改为写 order-cancelled 消息、由库存服务的消费者 release。
 */
@Service
public class OrderCloseService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final StockRestorer stockRestorer;
    private final TransactionTemplate transactionTemplate;

    public OrderCloseService(
            OrderMapper orderMapper,
            OrderItemMapper orderItemMapper,
            StockRestorer stockRestorer,
            TransactionTemplate transactionTemplate) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.stockRestorer = stockRestorer;
        this.transactionTemplate = transactionTemplate;
    }

    /** 返回 true 表示本次关掉了订单；用户已支付或已关闭时返回 false。 */
    public boolean closeIfUnpaid(String orderNo) {
        List<StockLine> lines = transactionTemplate.execute(status -> {
            Order order = orderMapper.selectByOrderNo(orderNo);
            if (order == null
                    || orderMapper.updateStatus(order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED)
                            == 0) {
                return null;
            }
            List<StockLine> items = orderItemMapper.selectByOrderId(order.getId()).stream()
                    .map(i -> new StockLine(i.getSkuId(), i.getQuantity()))
                    .toList();
            stockRestorer.enqueue(orderNo, items); // 与状态更新同生共死：提交了就一定有人去归还
            return items;
        });
        if (lines == null) {
            return false;
        }
        stockRestorer.tryRestore(orderNo, lines); // 失败留在任务表里，定时重试
        return true;
    }
}
