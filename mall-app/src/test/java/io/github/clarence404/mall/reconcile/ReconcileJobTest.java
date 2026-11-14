package io.github.clarence404.mall.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.clarence404.mall.common.outbox.OutboxWriter;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.reconcile.ReconcileChecker.Action;
import io.github.clarence404.mall.reconcile.ReconcileChecker.Diff;
import io.github.clarence404.mall.reconcile.ReconcileChecker.DiffType;
import io.github.clarence404.mall.reconcile.ReconcileChecker.PaymentRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 补发撞唯一键时看原消息的状态：已发送跳过；已失败（status = 2）写入差错表，不能当成「已补发过」放过。 */
class ReconcileJobTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    JdbcTemplate mall;
    OutboxWriter outboxWriter = mock(OutboxWriter.class);
    ReconcileJob job;

    Diff diff = new Diff(
            DiffType.PAID_BUT_ORDER_PENDING,
            "A",
            Action.REPUBLISH_PAYMENT_SUCCEEDED,
            new PaymentRow("A", "PA", 10001L, BigDecimal.TEN, 1, LocalDateTime.now()));

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:reconcile_job_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        mall = new JdbcTemplate(ds);
        mall.execute("CREATE TABLE t_outbox (topic VARCHAR(64), msg_key VARCHAR(64), status TINYINT)");
        mall.execute("CREATE TABLE t_reconcile_diff (id BIGINT, biz_date DATE, diff_type VARCHAR(32),"
                + " biz_key VARCHAR(64), detail VARCHAR(1024))");
        job = new ReconcileJob(
                mock(ReconcileReader.class),
                mall,
                outboxWriter,
                mock(InventoryApi.class),
                new TransactionTemplate(new DataSourceTransactionManager(ds)),
                new SimpleMeterRegistry());
        doThrow(new DuplicateKeyException("uk_topic_key"))
                .when(outboxWriter)
                .append(eq("payment-succeeded"), eq("PA"), any(), any());
    }

    @Test
    void republishHittingFailedOutboxRecordIsRecordedAsDiff() {
        mall.update("INSERT INTO t_outbox VALUES ('payment-succeeded', 'PA', 2)"); // 之前重试满 24 小时都没发出去

        job.apply(DAY, diff);

        assertThat(mall.queryForObject("SELECT diff_type FROM t_reconcile_diff WHERE biz_key = 'A'", String.class))
                .isEqualTo("PAID_BUT_ORDER_PENDING");
    }

    @Test
    void republishHittingSentOutboxRecordIsSkipped() {
        mall.update("INSERT INTO t_outbox VALUES ('payment-succeeded', 'PA', 1)");

        job.apply(DAY, diff);

        verify(outboxWriter).append(eq("payment-succeeded"), anyString(), any(), any());
        assertThat(mall.queryForObject("SELECT COUNT(*) FROM t_reconcile_diff", Integer.class))
                .isZero();
    }
}
