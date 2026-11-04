package io.github.clarence404.mall.reconcile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 每日对账的比对逻辑（纯函数，便于测试）：只看数据、不看过程。按 order_no 归并三个库的数据，输出差错与处理方式。
 *
 * <table>
 *   <caption>检查项</caption>
 *   <tr><td>支付成功，订单仍待支付</td><td>补发 payment-succeeded</td></tr>
 *   <tr><td>支付成功，订单已取消且未退款</td><td>补发 order-refund-required</td></tr>
 *   <tr><td>订单已被另一张支付单付过，这张支付成功且未退款（重复付款）</td><td>补发 order-refund-required</td></tr>
 *   <tr><td>订单已取消，预占仍 RESERVED / CONFIRMED</td><td>RESERVED 自动 release；CONFIRMED 转人工</td></tr>
 *   <tr><td>订单已支付，预占仍 RESERVED</td><td>自动 confirm</td></tr>
 *   <tr><td>某 SKU 的 reserved ≠ 同一快照里 RESERVED 记录数量之和</td><td>只告警，人工核对流水后修正</td></tr>
 * </table>
 */
public final class ReconcileChecker {

    static final int ORDER_PENDING_PAYMENT = 10;
    static final int ORDER_CANCELLED = 50;
    static final Set<Integer> ORDER_PAID = Set.of(20, 30, 40, 60);
    static final int PAYMENT_SUCCESS = 1;
    static final int RESERVED = 1;
    static final int CONFIRMED = 2;

    private ReconcileChecker() {}

    public static List<Diff> check(
            List<OrderRow> orders,
            List<PaymentRow> payments,
            List<ReservationRow> reservations,
            List<InventoryRow> inventories) {
        List<Diff> diffs = new ArrayList<>();
        Map<String, OrderRow> orderByNo =
                orders.stream().collect(Collectors.toMap(OrderRow::orderNo, Function.identity(), (a, b) -> a));

        for (PaymentRow p : payments) {
            OrderRow o = orderByNo.get(p.orderNo());
            if (o == null || p.status() != PAYMENT_SUCCESS) {
                continue;
            }
            if (o.status() == ORDER_PENDING_PAYMENT) {
                diffs.add(
                        new Diff(DiffType.PAID_BUT_ORDER_PENDING, p.orderNo(), Action.REPUBLISH_PAYMENT_SUCCEEDED, p));
            } else if (o.status() == ORDER_CANCELLED) {
                diffs.add(
                        new Diff(DiffType.PAID_BUT_ORDER_CANCELLED, p.orderNo(), Action.REPUBLISH_REFUND_REQUIRED, p));
            } else if (ORDER_PAID.contains(o.status())
                    && o.paymentNo() != null
                    && !o.paymentNo().equals(p.paymentNo())) {
                diffs.add(new Diff(DiffType.PAID_TWICE, p.orderNo(), Action.REPUBLISH_REFUND_REQUIRED, p));
            }
        }

        reservations.stream()
                .collect(Collectors.groupingBy(ReservationRow::orderNo))
                .forEach((orderNo, rows) -> {
                    OrderRow o = orderByNo.get(orderNo);
                    if (o == null) {
                        return; // 订单不存在的预占由库存服务的过期回查处理
                    }
                    boolean anyReserved = rows.stream().anyMatch(r -> r.status() == RESERVED);
                    boolean anyConfirmed = rows.stream().anyMatch(r -> r.status() == CONFIRMED);
                    if (o.status() == ORDER_CANCELLED && anyReserved) {
                        diffs.add(new Diff(DiffType.CANCELLED_BUT_RESERVED, orderNo, Action.RELEASE, rows));
                    }
                    if (o.status() == ORDER_CANCELLED && anyConfirmed) {
                        diffs.add(new Diff(DiffType.CANCELLED_BUT_CONFIRMED, orderNo, Action.MANUAL, rows));
                    }
                    if (ORDER_PAID.contains(o.status()) && anyReserved) {
                        diffs.add(new Diff(DiffType.PAID_BUT_RESERVED, orderNo, Action.CONFIRM, rows));
                    }
                });

        // 两个数取自库存库同一条语句（同一快照），见 ReconcileReader；不能拿当天变动的预占去比，否则几天前的活跃预占每晚都误报
        for (InventoryRow inv : inventories) {
            int expected = inv.activeReserved();
            if (inv.reserved() != expected) {
                diffs.add(new Diff(
                        DiffType.RESERVED_MISMATCH,
                        String.valueOf(inv.skuId()),
                        Action.MANUAL,
                        "reserved=" + inv.reserved() + ", RESERVED 记录之和=" + expected));
            }
        }
        return diffs;
    }

    public enum DiffType {
        PAID_BUT_ORDER_PENDING,
        PAID_BUT_ORDER_CANCELLED,
        PAID_TWICE,
        CANCELLED_BUT_RESERVED,
        CANCELLED_BUT_CONFIRMED,
        PAID_BUT_RESERVED,
        RESERVED_MISMATCH
    }

    /** 自动修复全部复用已有的幂等接口和消息，不写专用的修数据 SQL；修不了的写入差错表。 */
    public enum Action {
        REPUBLISH_PAYMENT_SUCCEEDED,
        REPUBLISH_REFUND_REQUIRED,
        RELEASE,
        CONFIRM,
        MANUAL
    }

    public record Diff(DiffType type, String bizKey, Action action, Object detail) {}

    /** paymentNo：把订单付成功的那张支付单，未支付为 null。 */
    public record OrderRow(String orderNo, int status, String paymentNo) {

        public OrderRow(String orderNo, int status) {
            this(orderNo, status, null);
        }
    }

    public record PaymentRow(
            String orderNo, String paymentNo, long userId, BigDecimal amount, int status, LocalDateTime paidAt) {}

    public record ReservationRow(String orderNo, long skuId, int quantity, int status) {}

    /** reserved 与 activeReserved（仍为 RESERVED 的预占之和）取自同一个快照。 */
    public record InventoryRow(long skuId, int reserved, int activeReserved) {}
}
