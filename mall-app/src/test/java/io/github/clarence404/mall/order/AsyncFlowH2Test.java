package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.clarence404.mall.order.api.MarkPaidRequest;
import io.github.clarence404.mall.order.api.MarkPaidResult;
import io.github.clarence404.mall.order.cancel.OrderCloseService;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.paid.OrderPaidTxService;
import io.github.clarence404.mall.order.place.AddressForm;
import io.github.clarence404.mall.order.place.OrderLine;
import io.github.clarence404.mall.order.place.PlaceOrderRequest;
import io.github.clarence404.mall.order.place.PlaceOrderService;
import io.github.clarence404.mall.support.H2TestBase;
import io.github.clarence404.mall.user.points.OrderPaidEvent;
import io.github.clarence404.mall.user.points.PointsGrantService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 异步化的落库部分走真实 SQL（H2 MySQL 模式，RocketMQ 关闭）：下单同事务写 order-timeout 定时消息，
 * 支付成功同事务写 order-paid，积分消费按 t_consume_record 去重，关单按状态条件更新。
 */
class AsyncFlowH2Test extends H2TestBase {

    @Autowired
    PlaceOrderService placeOrderService;

    @Autowired
    OrderPaidTxService orderPaidTxService;

    @Autowired
    PointsGrantService pointsGrantService;

    @Autowired
    OrderCloseService orderCloseService;

    @Autowired
    JdbcTemplate jdbc;

    PlaceOrderRequest request() {
        return new PlaceOrderRequest(
                List.of(new OrderLine(20002L, 1)), new AddressForm("张三", "13800000000", "杭州市西湖区"), null);
    }

    @Test
    void placeOrderWritesTimeoutMessageDue15MinutesLater() {
        Order order = placeOrderService
                .place(10001L, UUID.randomUUID().toString(), request())
                .order();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT topic, status, deliver_at FROM t_outbox WHERE msg_key = ? AND topic = 'order-timeout'",
                order.getOrderNo());
        assertThat(row.get("status")).isEqualTo(0);
        LocalDateTime deliverAt = ((Timestamp) row.get("deliver_at")).toLocalDateTime();
        assertThat(Duration.between(order.getCreatedAt(), deliverAt)
                        .abs()
                        .minus(Duration.ofMinutes(15))
                        .abs())
                .isLessThan(Duration.ofMillis(2)); // DATETIME(3) 只存到毫秒
    }

    @Test
    void markPaidWritesOrderPaidOnceEvenIfRepeated() {
        Order order = placeOrderService
                .place(10001L, UUID.randomUUID().toString(), request())
                .order();
        MarkPaidRequest req = new MarkPaidRequest("P1", order.getPayAmount(), LocalDateTime.now());

        assertThat(orderPaidTxService.markPaid(order.getOrderNo(), req)).isEqualTo(MarkPaidResult.PAID);
        assertThat(orderPaidTxService.markPaid(order.getOrderNo(), req)).isEqualTo(MarkPaidResult.ALREADY_PAID);

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE msg_key = ? AND topic = 'order-paid'",
                        Integer.class,
                        order.getOrderNo()))
                .isEqualTo(1);
        // 已支付后到期的超时消息：条件更新影响 0 行，直接确认
        assertThat(orderCloseService.closeIfUnpaid(order.getOrderNo())).isFalse();
    }

    @Test
    void paymentArrivingAfterTimeoutCloseReportsCancelled() {
        Order order = placeOrderService
                .place(10001L, UUID.randomUUID().toString(), request())
                .order();
        assertThat(orderCloseService.closeIfUnpaid(order.getOrderNo())).isTrue();

        assertThat(orderPaidTxService.markPaid(
                        order.getOrderNo(), new MarkPaidRequest("P2", order.getPayAmount(), LocalDateTime.now())))
                .isEqualTo(MarkPaidResult.ORDER_CANCELLED);
    }

    @Test
    void pointsAreGrantedOncePerOrder() {
        OrderPaidEvent e =
                new OrderPaidEvent("P" + System.nanoTime(), 10002L, new BigDecimal("59.99"), LocalDateTime.now());

        pointsGrantService.grant(e);
        pointsGrantService.grant(e); // 重复投递

        assertThat(jdbc.queryForObject(
                        "SELECT SUM(points) FROM t_points_log WHERE order_no = ?", Integer.class, e.orderNo()))
                .isEqualTo(59);
    }

    @Test
    void timeoutClosesUnpaidOrderOnceAndRestoresStockOnce() {
        Order order = placeOrderService
                .place(10001L, UUID.randomUUID().toString(), request())
                .order();

        assertThat(orderCloseService.closeIfUnpaid(order.getOrderNo())).isTrue();
        assertThat(orderCloseService.closeIfUnpaid(order.getOrderNo())).isFalse(); // 重复消息

        verify(inventoryApi, times(1))
                .restore(org.mockito.ArgumentMatchers.argThat(r -> r.orderNo().equals(order.getOrderNo())));
        assertThat(jdbc.queryForObject("SELECT status FROM t_order WHERE id = ?", Integer.class, order.getId()))
                .isEqualTo(50);
    }
}
