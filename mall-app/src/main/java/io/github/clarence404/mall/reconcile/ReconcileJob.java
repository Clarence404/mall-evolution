package io.github.clarence404.mall.reconcile;

import com.zaxxer.hikari.HikariDataSource;
import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.common.outbox.OutboxWriter;
import io.github.clarence404.mall.inventory.api.ConfirmStockRequest;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.ReleaseStockRequest;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.reconcile.ReconcileChecker.Diff;
import io.github.clarence404.mall.reconcile.ReconcileChecker.PaymentRow;
import io.github.clarence404.mall.reconcile.ReconcileChecker.ReservationRow;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 每日凌晨 2 点的对账任务。消息、回查都依赖代码按预期运行；对账是唯一「只看数据、不看过程」的检查。
 *
 * <p>三个只读从库的连接在这里自建，不注册成 DataSource Bean，免得顶掉应用自己的数据源。取数口径见 {@link ReconcileReader}。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mall.reconcile", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ReconcileProperties.class)
class ReconcileJob implements DisposableBean {

    private final List<HikariDataSource> replicas;
    private final ReconcileReader reader;
    private final JdbcTemplate diffTable;
    private final OutboxWriter outboxWriter;
    private final InventoryApi inventoryApi;
    private final TransactionTemplate transactionTemplate;
    private final AtomicInteger lastDiffCount = new AtomicInteger();

    ReconcileJob(
            ReconcileProperties props,
            JdbcTemplate jdbcTemplate,
            OutboxWriter outboxWriter,
            InventoryApi inventoryApi,
            TransactionTemplate transactionTemplate,
            MeterRegistry registry) {
        HikariDataSource mallDs = readOnly(props.mall(), "reconcile-mall");
        HikariDataSource inventoryDs = readOnly(props.inventory(), "reconcile-inventory");
        HikariDataSource paymentDs = readOnly(props.payment(), "reconcile-payment");
        this.replicas = List.of(mallDs, inventoryDs, paymentDs);
        this.reader = new ReconcileReader(
                new JdbcTemplate(mallDs), new JdbcTemplate(inventoryDs), new JdbcTemplate(paymentDs));
        this.diffTable = jdbcTemplate;
        this.outboxWriter = outboxWriter;
        this.inventoryApi = inventoryApi;
        this.transactionTemplate = transactionTemplate;
        Gauge.builder("reconcile.diff.count", lastDiffCount, AtomicInteger::get)
                .description("最近一次每日对账发现的差异数")
                .register(registry);
    }

    /** 测试用：直接给取数器，不建只读连接池。 */
    ReconcileJob(
            ReconcileReader reader,
            JdbcTemplate jdbcTemplate,
            OutboxWriter outboxWriter,
            InventoryApi inventoryApi,
            TransactionTemplate transactionTemplate,
            MeterRegistry registry) {
        this.replicas = List.of();
        this.reader = reader;
        this.diffTable = jdbcTemplate;
        this.outboxWriter = outboxWriter;
        this.inventoryApi = inventoryApi;
        this.transactionTemplate = transactionTemplate;
        Gauge.builder("reconcile.diff.count", lastDiffCount, AtomicInteger::get)
                .description("最近一次每日对账发现的差异数")
                .register(registry);
    }

    @Scheduled(cron = "${mall.reconcile.cron:0 0 2 * * *}")
    void reconcileYesterday() {
        reconcile(LocalDate.now().minusDays(1));
    }

    /** 取对账日内任一表有变动的订单，按订单号取三个库的全量数据比对。 */
    List<Diff> reconcile(LocalDate day) {
        ReconcileReader.Snapshot s =
                reader.read(day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        List<Diff> diffs = ReconcileChecker.check(s.orders(), s.payments(), s.reservations(), s.inventories());
        lastDiffCount.set(diffs.size());
        diffs.forEach(d -> apply(day, d));
        log.info("每日对账完成，日期={}，涉及订单 {} 个，差异 {} 条", day, s.orders().size(), diffs.size());
        return diffs;
    }

    void apply(LocalDate day, Diff d) {
        try {
            switch (d.action()) {
                case REPUBLISH_PAYMENT_SUCCEEDED -> republish(day, d, "payment-succeeded");
                case REPUBLISH_REFUND_REQUIRED -> republish(day, d, "order-refund-required");
                case RELEASE -> inventoryApi.release(new ReleaseStockRequest(d.bizKey(), reservedLines(d)));
                case CONFIRM -> inventoryApi.confirm(new ConfirmStockRequest(d.bizKey()));
                case MANUAL -> recordDiff(day, d);
            }
        } catch (RuntimeException e) {
            log.warn("自动修复失败，写入差错表，type={}, key={}", d.type(), d.bizKey(), e);
            recordDiff(day, d);
        }
    }

    /**
     * 补发走正常消费路径：写进本服务的 t_outbox（消息键是支付单号），由中继投递，消费端照常幂等。
     *
     * <p>撞唯一键说明这条消息以前写过：已发送或待发送就跳过；原记录已经是失败（status = 2，重试 10 次都没发出去）时，
     * 补发其实没生效，写入差错表交给人工，而不是当成「已补发过」悄悄放过。
     */
    private void republish(LocalDate day, Diff d, String topic) {
        PaymentRow p = (PaymentRow) d.detail();
        try {
            transactionTemplate.executeWithoutResult(s -> outboxWriter.append(
                    topic,
                    p.paymentNo(),
                    Map.of(
                            "orderNo", p.orderNo(),
                            "paymentNo", p.paymentNo(),
                            "userId", p.userId(),
                            "amount", p.amount(),
                            "paidAt", p.paidAt() == null ? LocalDateTime.now() : p.paidAt()),
                    null));
        } catch (DuplicateKeyException e) {
            List<Integer> status = diffTable.queryForList(
                    "SELECT status FROM t_outbox WHERE topic = ? AND msg_key = ?", Integer.class, topic, p.paymentNo());
            if (status.contains(2)) {
                log.warn("补发的消息之前已发送失败，写入差错表，topic={}, paymentNo={}", topic, p.paymentNo());
                recordDiff(day, d);
            } else {
                log.info("已补发过，跳过，topic={}, paymentNo={}", topic, p.paymentNo());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ReserveLine> reservedLines(Diff d) {
        return ((List<ReservationRow>) d.detail())
                .stream()
                        .filter(r -> r.status() == ReconcileChecker.RESERVED)
                        .map(r -> new ReserveLine(r.skuId(), r.quantity()))
                        .toList();
    }

    private void recordDiff(LocalDate day, Diff d) {
        diffTable.update(
                "INSERT INTO t_reconcile_diff (id, biz_date, diff_type, biz_key, detail) VALUES (?, ?, ?, ?, ?)",
                Ids.next(),
                day,
                d.type().name(),
                d.bizKey(),
                String.valueOf(d.detail()));
    }

    private static HikariDataSource readOnly(ReconcileProperties.Source s, String name) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName(name);
        ds.setJdbcUrl(s.url());
        ds.setUsername(s.username());
        ds.setPassword(s.password());
        ds.setReadOnly(true);
        ds.setMaximumPoolSize(2);
        return ds;
    }

    @Override
    public void destroy() {
        replicas.forEach(HikariDataSource::close);
    }
}
