package io.github.clarence404.mall.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.reconcile.ReconcileChecker.Action;
import io.github.clarence404.mall.reconcile.ReconcileChecker.Diff;
import io.github.clarence404.mall.reconcile.ReconcileChecker.DiffType;
import io.github.clarence404.mall.reconcile.ReconcileChecker.InventoryRow;
import io.github.clarence404.mall.reconcile.ReconcileChecker.OrderRow;
import io.github.clarence404.mall.reconcile.ReconcileChecker.PaymentRow;
import io.github.clarence404.mall.reconcile.ReconcileChecker.ReservationRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconcileCheckerTest {

    @Test
    void findsEachKindOfInconsistencyAndPicksIdempotentRepair() {
        List<OrderRow> orders = List.of(
                new OrderRow("A", 10), // 支付成功但订单仍待支付
                new OrderRow("B", 50), // 支付成功但订单已取消
                new OrderRow("C", 50), // 取消但预占仍 RESERVED
                new OrderRow("D", 50), // 取消但预占已 CONFIRMED
                new OrderRow("E", 20), // 已支付但预占仍 RESERVED
                new OrderRow("F", 20)); // 一致
        List<PaymentRow> payments = List.of(pay("A", 1), pay("B", 1), pay("F", 1), pay("C", 2));
        List<ReservationRow> reservations = List.of(
                new ReservationRow("C", 20001L, 2, 1),
                new ReservationRow("D", 20001L, 1, 2),
                new ReservationRow("E", 20002L, 1, 1),
                new ReservationRow("F", 20002L, 1, 2));
        List<InventoryRow> inventories = List.of(
                new InventoryRow(20001L, 2, 2), // 与同一快照里的活跃预占之和相等，正确
                new InventoryRow(20002L, 5, 1)); // 应为 1，对不上

        List<Diff> diffs = ReconcileChecker.check(orders, payments, reservations, inventories);

        assertThat(diffs)
                .extracting(Diff::type, Diff::bizKey, Diff::action)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                DiffType.PAID_BUT_ORDER_PENDING, "A", Action.REPUBLISH_PAYMENT_SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(
                                DiffType.PAID_BUT_ORDER_CANCELLED, "B", Action.REPUBLISH_REFUND_REQUIRED),
                        org.assertj.core.groups.Tuple.tuple(DiffType.CANCELLED_BUT_RESERVED, "C", Action.RELEASE),
                        org.assertj.core.groups.Tuple.tuple(DiffType.CANCELLED_BUT_CONFIRMED, "D", Action.MANUAL),
                        org.assertj.core.groups.Tuple.tuple(DiffType.PAID_BUT_RESERVED, "E", Action.CONFIRM),
                        org.assertj.core.groups.Tuple.tuple(DiffType.RESERVED_MISMATCH, "20002", Action.MANUAL));
    }

    @Test
    void consistentDataProducesNoDiff() {
        assertThat(ReconcileChecker.check(
                        List.of(new OrderRow("F", 20)),
                        List.of(pay("F", 1)),
                        List.of(new ReservationRow("F", 20002L, 1, 2)),
                        List.of(new InventoryRow(20002L, 0, 0))))
                .isEmpty();
    }

    private static PaymentRow pay(String orderNo, int status) {
        return new PaymentRow(orderNo, "P-" + orderNo, 10001L, BigDecimal.TEN, status, LocalDateTime.now());
    }

    @Test
    void secondSuccessfulPaymentOfPaidOrderIsRefunded() {
        List<Diff> diffs = ReconcileChecker.check(
                List.of(new OrderRow("G", 20, "P-G1")),
                List.of(
                        new PaymentRow("G", "P-G1", 10001L, BigDecimal.TEN, 1, LocalDateTime.now()),
                        new PaymentRow("G", "P-G2", 10001L, BigDecimal.TEN, 1, LocalDateTime.now())),
                List.of(),
                List.of());

        assertThat(diffs).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(DiffType.PAID_TWICE);
            assertThat(d.action()).isEqualTo(Action.REPUBLISH_REFUND_REQUIRED);
            assertThat(((PaymentRow) d.detail()).paymentNo()).isEqualTo("P-G2");
        });
    }

    @Test
    void activeReservationsFromEarlierDaysDoNotCauseMismatch() {
        // 预占是三天前的、今天没有变动：reserved 与同一快照里的活跃预占之和一致，不报
        assertThat(ReconcileChecker.check(List.of(), List.of(), List.of(), List.of(new InventoryRow(20001L, 3, 3))))
                .isEmpty();
    }
}
