package io.github.clarence404.mall.reconcile;

import io.github.clarence404.mall.reconcile.ReconcileChecker.InventoryRow;
import io.github.clarence404.mall.reconcile.ReconcileChecker.OrderRow;
import io.github.clarence404.mall.reconcile.ReconcileChecker.PaymentRow;
import io.github.clarence404.mall.reconcile.ReconcileChecker.ReservationRow;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 对账取数：先取对账日内三个库任一表有变动的 order_no 集合，再按这些 order_no 去三个库取全量记录比对。
 *
 * <p>只按各表自己的 updated_at 取数会漏掉跨零点的单：23:59 下单、00:01 支付成功，订单表落在前一天、支付表落在后一天，
 * 两天对账都只看到一半。按订单号取全量后，任一侧当天有变动，三边的数据就都在。
 *
 * <p>预占总量用库存库里的一条语句同时读 t_inventory.reserved 与仍为 RESERVED 的预占之和：同一条语句是同一个一致性快照，
 * 不会因为两次查询之间又有新预占而每晚误报；也不再只看当天变动的预占，几天前的活跃预占同样算进去。
 */
class ReconcileReader {

    private static final int CHUNK = 500;

    private final JdbcTemplate mall;
    private final JdbcTemplate inventory;
    private final JdbcTemplate payment;

    ReconcileReader(JdbcTemplate mall, JdbcTemplate inventory, JdbcTemplate payment) {
        this.mall = mall;
        this.inventory = inventory;
        this.payment = payment;
    }

    record Snapshot(
            List<OrderRow> orders,
            List<PaymentRow> payments,
            List<ReservationRow> reservations,
            List<InventoryRow> inventories) {}

    Snapshot read(LocalDateTime from, LocalDateTime to) {
        Timestamp f = Timestamp.valueOf(from);
        Timestamp t = Timestamp.valueOf(to);
        TreeSet<String> orderNos = new TreeSet<>();
        orderNos.addAll(mall.queryForList(
                "SELECT order_no FROM t_order WHERE updated_at >= ? AND updated_at < ?", String.class, f, t));
        orderNos.addAll(payment.queryForList(
                "SELECT DISTINCT order_no FROM t_payment WHERE updated_at >= ? AND updated_at < ?",
                String.class,
                f,
                t));
        orderNos.addAll(inventory.queryForList(
                "SELECT DISTINCT order_no FROM t_inventory_reservation WHERE updated_at >= ? AND updated_at < ?",
                String.class,
                f,
                t));

        List<OrderRow> orders = new ArrayList<>();
        List<PaymentRow> payments = new ArrayList<>();
        List<ReservationRow> reservations = new ArrayList<>();
        List<String> all = new ArrayList<>(orderNos);
        for (int i = 0; i < all.size(); i += CHUNK) {
            Map<String, Object> in = Map.of("orderNos", all.subList(i, Math.min(i + CHUNK, all.size())));
            orders.addAll(new NamedParameterJdbcTemplate(mall)
                    .query(
                            "SELECT order_no, status, payment_no FROM t_order WHERE order_no IN (:orderNos)",
                            in,
                            (rs, n) -> new OrderRow(rs.getString(1), rs.getInt(2), rs.getString(3))));
            payments.addAll(new NamedParameterJdbcTemplate(payment)
                    .query(
                            "SELECT order_no, payment_no, user_id, amount, status, paid_at FROM t_payment"
                                    + " WHERE order_no IN (:orderNos)",
                            in,
                            (rs, n) -> new PaymentRow(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getLong(3),
                                    rs.getBigDecimal(4),
                                    rs.getInt(5),
                                    rs.getTimestamp(6) == null
                                            ? null
                                            : rs.getTimestamp(6).toLocalDateTime())));
            reservations.addAll(new NamedParameterJdbcTemplate(inventory)
                    .query(
                            "SELECT order_no, sku_id, quantity, status FROM t_inventory_reservation"
                                    + " WHERE order_no IN (:orderNos)",
                            in,
                            (rs, n) -> new ReservationRow(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getInt(4))));
        }

        // 分桶的 SKU（热点库存）：预占记在桶上，t_inventory.reserved 加上各桶 reserved 才是总预占
        List<InventoryRow> inventories =
                inventory.query("""
                SELECT i.sku_id,
                       i.reserved + COALESCE((SELECT SUM(b.reserved) FROM t_inventory_bucket b WHERE b.sku_id = i.sku_id), 0),
                       COALESCE((SELECT SUM(r.quantity) FROM t_inventory_reservation r
                                 WHERE r.sku_id = i.sku_id AND r.status = 1), 0)
                FROM t_inventory i
                """, (rs, n) -> new InventoryRow(rs.getLong(1), rs.getInt(2), rs.getInt(3)));
        return new Snapshot(orders, payments, reservations, inventories);
    }
}
