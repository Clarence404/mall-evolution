package io.github.clarence404.mall.order.cancel;

import io.github.clarence404.mall.common.outbox.OutboxWriter;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.payment.api.ClosePaymentResult;
import io.github.clarence404.mall.payment.api.PaymentApi;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 取消订单（用户取消，或 order-timeout-consumer 收到超时消息）：先请 payment-service 关闭支付单，关闭成功后在本地事务里
 * 把订单从 10 改为 50 并写入 order-cancelled，库存服务的 inventory-release-consumer 收到后执行 release。
 *
 * <p>关闭支付单返回 PAID 说明用户已经付款，这时不取消，等 payment-succeeded 到达。
 */
@Slf4j
@Service
public class OrderCloseService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentApi paymentApi;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;

    public OrderCloseService(
            OrderMapper orderMapper,
            OrderItemMapper orderItemMapper,
            PaymentApi paymentApi,
            OutboxWriter outboxWriter,
            TransactionTemplate transactionTemplate) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.paymentApi = paymentApi;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
    }

    /** 返回 true 表示本次关掉了订单；已支付、已关闭或用户恰好付了款时返回 false。 */
    public boolean closeIfUnpaid(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return false;
        }
        if (paymentApi.close(orderNo) == ClosePaymentResult.PAID) {
            log.info("关单时发现用户已付款，不取消，等待 payment-succeeded，orderNo={}", orderNo);
            return false;
        }
        Boolean closed = transactionTemplate.execute(status -> {
            if (orderMapper.transit(orderNo, OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, null) == 0) {
                return false;
            }
            List<ReserveLine> lines = orderItemMapper.selectByOrderId(order.getId()).stream()
                    .map(i -> new ReserveLine(i.getSkuId(), i.getQuantity()))
                    .toList();
            outboxWriter.append("order-cancelled", orderNo, new OrderCancelledEvent(orderNo, lines), null);
            return true;
        });
        return Boolean.TRUE.equals(closed);
    }
}
