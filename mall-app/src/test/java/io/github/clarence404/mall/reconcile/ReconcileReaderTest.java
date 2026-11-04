package io.github.clarence404.mall.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.reconcile.ReconcileChecker.Diff;
import io.github.clarence404.mall.reconcile.ReconcileChecker.DiffType;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 对账取数口径：按「任一表当天有变动」的订单号取三个库的全量数据；预占总量按同一快照比对。H2 模拟三个库。 */
class ReconcileReaderTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    JdbcTemplate mall;
    JdbcTemplate inventory;
    JdbcTemplate payment;
    ReconcileReader reader;

    @BeforeEach
    void createDatabases() {
        mall = h2("mall");
        inventory = h2("inventory");
        payment = h2("payment");
        mall.execute("CREATE TABLE t_order (order_no VARCHAR(32) PRIMARY KEY, status TINYINT, payment_no VARCHAR(32),"
                + " updated_at DATETIME(3))");
        payment.execute(
                "CREATE TABLE t_payment (payment_no VARCHAR(32) PRIMARY KEY, order_no VARCHAR(32),"
                        + " user_id BIGINT, amount DECIMAL(12,2), status TINYINT, paid_at DATETIME(3), updated_at DATETIME(3))");
        inventory.execute("CREATE TABLE t_inventory (sku_id BIGINT PRIMARY KEY, stock INT, reserved INT,"
                + " updated_at DATETIME(3))");
        inventory.execute("CREATE TABLE t_inventory_bucket (sku_id BIGINT, bucket_no INT, stock INT, reserved INT)");
        inventory.execute("CREATE TABLE t_inventory_reservation (order_no VARCHAR(32), sku_id BIGINT, quantity INT,"
                + " status TINYINT, updated_at DATETIME(3))");
        reader = new ReconcileReader(mall, inventory, payment);
    }

    @Test
    void orderPlacedBeforeMidnightAndPaidAfterIsStillCompared() {
        // 23:59 下单（订单表落在对账日），00:01 支付成功（支付表落在第二天），payment-succeeded 丢了，订单还是待支付
        mall.update("INSERT INTO t_order VALUES ('A', 10, NULL, ?)", at(DAY, 23, 59));
        payment.update(
                "INSERT INTO t_payment VALUES ('PA', 'A', 10001, 9.90, 1, ?, ?)",
                at(DAY.plusDays(1), 0, 1),
                at(DAY.plusDays(1), 0, 1));

        List<Diff> diffs = check(DAY);

        assertThat(diffs)
                .extracting(Diff::type, Diff::bizKey)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DiffType.PAID_BUT_ORDER_PENDING, "A"));
    }

    @Test
    void paymentChangedOnDayPullsOrderLastUpdatedEarlier() {
        // 订单前天就取消了，今天才到账：只按各表当天变动取数时订单不在窗口里，这笔永远对不出来
        mall.update("INSERT INTO t_order VALUES ('B', 50, NULL, ?)", at(DAY.minusDays(2), 10, 0));
        payment.update("INSERT INTO t_payment VALUES ('PB', 'B', 10001, 9.90, 1, ?, ?)", at(DAY, 9, 0), at(DAY, 9, 0));

        assertThat(check(DAY)).extracting(Diff::type).containsExactly(DiffType.PAID_BUT_ORDER_CANCELLED);
    }

    @Test
    void activeReservationsFromEarlierDaysDoNotCauseMismatch() {
        // 三天前的预占仍为 RESERVED（订单待支付中），今天没有任何变动
        inventory.update("INSERT INTO t_inventory VALUES (20001, 97, 3, ?)", at(DAY.minusDays(3), 8, 0));
        inventory.update(
                "INSERT INTO t_inventory_reservation VALUES ('C', 20001, 3, 1, ?)", at(DAY.minusDays(3), 8, 0));
        inventory.update("INSERT INTO t_inventory VALUES (20002, 10, 0, ?)", at(DAY, 8, 0)); // 当天变动、无预占

        assertThat(check(DAY)).isEmpty();
    }

    @Test
    void reservedCounterDriftIsReported() {
        inventory.update("INSERT INTO t_inventory VALUES (20003, 95, 5, ?)", at(DAY, 8, 0));
        inventory.update("INSERT INTO t_inventory_reservation VALUES ('D', 20003, 2, 1, ?)", at(DAY, 8, 0));
        mall.update("INSERT INTO t_order VALUES ('D', 10, NULL, ?)", at(DAY, 8, 0));

        assertThat(check(DAY))
                .extracting(Diff::type, Diff::bizKey)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DiffType.RESERVED_MISMATCH, "20003"));
    }

    @Test
    void bucketedSkuCountsReservedOnBuckets() {
        // 热点 SKU 分桶：预占记在桶上，t_inventory.reserved 为 0
        inventory.update("INSERT INTO t_inventory VALUES (20004, 0, 0, ?)", at(DAY, 8, 0));
        inventory.update("INSERT INTO t_inventory_bucket VALUES (20004, 0, 10, 1), (20004, 1, 10, 2)");
        inventory.update("INSERT INTO t_inventory_reservation VALUES ('E', 20004, 3, 1, ?)", at(DAY, 8, 0));
        mall.update("INSERT INTO t_order VALUES ('E', 10, NULL, ?)", at(DAY, 8, 0));

        assertThat(check(DAY)).isEmpty();
    }

    private List<Diff> check(LocalDate day) {
        ReconcileReader.Snapshot s =
                reader.read(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        return ReconcileChecker.check(s.orders(), s.payments(), s.reservations(), s.inventories());
    }

    private static Timestamp at(LocalDate day, int hour, int minute) {
        return Timestamp.valueOf(LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)));
    }

    private static JdbcTemplate h2(String name) {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:reconcile_" + name + "_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }
}
