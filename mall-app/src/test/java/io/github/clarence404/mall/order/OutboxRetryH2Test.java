package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.common.outbox.OutboxMapper;
import io.github.clarence404.mall.support.H2TestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 本地消息表的重试回写走真实 SQL（H2 MySQL 模式，不需要 Docker）：按时间封顶，而不是按次数。
 *
 * <p>修复前第 10 次失败（约 9 分钟）就置为失败、再也不发；Broker 抖动十来分钟就会留下一批要人工处理的记录。
 */
class OutboxRetryH2Test extends H2TestBase {

    static final int ONE_DAY = 24 * 3600;

    @Autowired
    OutboxMapper outboxMapper;

    long id;

    @BeforeEach
    void insertPending() {
        jdbcTemplate.update("DELETE FROM t_outbox");
        id = Ids.next();
        jdbcTemplate.update(
                "INSERT INTO t_outbox (id, topic, msg_key, payload, status, retry_count, next_retry_at)"
                        + " VALUES (?, 'order-paid', 'K1', '{}', 0, 0, CURRENT_TIMESTAMP(3))",
                id);
    }

    /** 失败 10 次、20 次都还是待发送：只要创建时间在 24 小时内就继续重试。 */
    @Test
    void keepsRetryingPastTenFailuresWhileWithinTheTimeLimit() {
        for (int i = 0; i < 20; i++) {
            outboxMapper.markRetry(id, 240, ONE_DAY);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM t_outbox", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT retry_count FROM t_outbox", Integer.class))
                .isEqualTo(20);
    }

    /** next_retry_at 推后传入的退避秒数（退避怎么算见 OutboxRelayTest）。 */
    @Test
    void nextRetryIsPushedBackByTheGivenBackoff() {
        outboxMapper.markRetry(id, 240, ONE_DAY);

        assertThat(secondsUntilNextRetry()).isBetween(236L, 241L);
    }

    /** 创建超过 24 小时还没发出去：置为失败，交给 OutboxFailedRecords 告警与人工处理。 */
    @Test
    void givesUpOnceTheRecordIsOlderThanTheTimeLimit() {
        jdbcTemplate.update("UPDATE t_outbox SET created_at = DATEADD('HOUR', -25, CURRENT_TIMESTAMP(3))");

        outboxMapper.markRetry(id, 240, ONE_DAY);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM t_outbox", Integer.class))
                .isEqualTo(2);
    }

    /** 消息本身非法（Broker 明确拒收）：不用等 24 小时，直接置为失败。 */
    @Test
    void markFailedIsTerminal() {
        outboxMapper.markFailed(id);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM t_outbox", Integer.class))
                .isEqualTo(2);
        assertThat(outboxMapper.countFailed()).isEqualTo(1);
    }

    private long secondsUntilNextRetry() {
        return jdbcTemplate.queryForObject(
                "SELECT DATEDIFF('SECOND', CURRENT_TIMESTAMP(3), next_retry_at) FROM t_outbox", Long.class);
    }
}
