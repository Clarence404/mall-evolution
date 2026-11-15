package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.common.outbox.OutboxAdminService;
import io.github.clarence404.mall.common.outbox.OutboxMapper;
import io.github.clarence404.mall.common.outbox.OutboxRedriveResult;
import io.github.clarence404.mall.common.outbox.OutboxRelay;
import io.github.clarence404.mall.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 中继在真实 MySQL 上的行为：两个实例同时扫表，FOR UPDATE SKIP LOCKED 保证每条只发一次；Broker 失败按退避重试、按时间封顶；非法消息立即置失败；
 * 失败记录经重投接口背后的 OutboxAdminService 放回待发送后被中继重新发出，放弃计时从重投时刻重新算。
 * Producer 用替身（Testcontainers 没有 RocketMQ 模块）。需要 Docker。
 */
class OutboxRelayIT extends IntegrationTestBase {

    @Autowired
    OutboxMapper outboxMapper;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxAdminService outboxAdminService;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM t_outbox");
    }

    void insertDue(int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update(
                    "INSERT INTO t_outbox (id, topic, msg_key, payload, status, retry_count, next_retry_at)"
                            + " VALUES (?, 'order-paid', ?, JSON_OBJECT('orderNo', ?), 0, 0, NOW(3) - INTERVAL 1 SECOND)",
                    Ids.next(),
                    "K" + i,
                    "K" + i);
        }
    }

    @Test
    void twoRelaysScanningConcurrentlySendEachRecordOnce() throws Exception {
        insertDue(300);
        ConcurrentLinkedQueue<String> sentKeys = new ConcurrentLinkedQueue<>();
        Producer producer = mock(Producer.class);
        ConcurrentLinkedQueue<Boolean> sendInsideTx = new ConcurrentLinkedQueue<>();
        when(producer.send(any(Message.class))).thenAnswer(inv -> {
            sendInsideTx.add(TransactionSynchronizationManager.isActualTransactionActive());
            sentKeys.add(inv.<Message>getArgument(0).getKeys().iterator().next());
            return null;
        });
        OutboxRelay a =
                new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), producer, transactionTemplate);
        OutboxRelay b =
                new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), producer, transactionTemplate);

        // 一轮并发扫表不保证扫完：被别的事务锁住的行直接跳过，等下一轮再发（OutboxRelayJob 每秒一轮）。
        // 这里要验证的是「扫到清空为止，每条恰好发一次」，所以按轮次重复，直到表里没有待发送的记录。
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (int round = 0; round < 20; round++) {
                List<Callable<Integer>> tasks = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    OutboxRelay relay = i % 2 == 0 ? a : b;
                    tasks.add(() -> relay.relayBatch(100));
                }
                for (Future<Integer> f : pool.invokeAll(tasks)) {
                    f.get();
                }
                if (jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox WHERE status = 0", Integer.class) == 0) {
                    break;
                }
            }
        }

        assertThat(sentKeys).hasSize(300).doesNotHaveDuplicates();
        assertThat(sendInsideTx).containsOnly(false); // 发送时认领事务已提交，不持有行锁与数据库连接
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox WHERE status = 1", Integer.class))
                .isEqualTo(300);
    }

    @Test
    void claimedRecordsAreLeasedSoAnotherRelaySkipsThem() throws Exception {
        insertDue(3);
        Producer producer = mock(Producer.class);
        OutboxRelay other =
                new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), producer, transactionTemplate);

        // 只认领、不发送（模拟第一个实例正在发送这批）：租约生效后另一个实例扫不到
        transactionTemplate.executeWithoutResult(s -> outboxMapper.lease(
                outboxMapper.selectDueForUpdate(10).stream().map(r -> r.id()).toList(), 60));

        assertThat(other.relayBatch(10)).isZero();
        verify(producer, never()).send(any(Message.class));
    }

    /** Broker 故障：按退避重试，失败多少次都不置失败；创建超过 24 小时还没发出去才置为失败。 */
    @Test
    void brokerFailureKeepsRetryingAndOnlyGivesUpAfterTheTimeLimit() throws Exception {
        insertDue(1);
        Producer producer = mock(Producer.class);
        when(producer.send(any(Message.class))).thenThrow(new ClientException("down"));
        OutboxRelay relay =
                new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), producer, transactionTemplate);

        relay.relayBatch(10);

        verify(producer, atLeastOnce()).send(any(Message.class));
        assertThat(jdbc.queryForObject("SELECT retry_count FROM t_outbox", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_retry_at > NOW(3) FROM t_outbox", Boolean.class))
                .isTrue();

        // 已经失败 30 次：原先第 10 次就置失败，现在仍是待发送，下一次重试最多等 4 分钟
        jdbc.update("UPDATE t_outbox SET retry_count = 30, next_retry_at = NOW(3) - INTERVAL 1 SECOND");
        relay.relayBatch(10);
        assertThat(jdbc.queryForObject("SELECT status FROM t_outbox", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT retry_count FROM t_outbox", Integer.class))
                .isEqualTo(31);
        assertThat(jdbc.queryForObject(
                        "SELECT TIMESTAMPDIFF(SECOND, NOW(3), next_retry_at) BETWEEN 230 AND 241 FROM t_outbox",
                        Boolean.class))
                .isTrue();

        // 创建已超过 24 小时：这次失败后置为失败，不再认领
        jdbc.update("UPDATE t_outbox SET created_at = NOW(3) - INTERVAL 25 HOUR,"
                + " next_retry_at = NOW(3) - INTERVAL 1 SECOND");
        relay.relayBatch(10);
        assertThat(jdbc.queryForObject("SELECT status FROM t_outbox", Integer.class))
                .isEqualTo(2);
        org.mockito.Mockito.clearInvocations(producer);
        assertThat(relay.relayBatch(10)).isZero();
        verify(producer, never()).send(any(Message.class));
    }

    /** Broker 拒收（消息本身非法）：立即置为失败，同一批里后面的记录照常发出。 */
    @Test
    void illegalMessageFailsImmediatelyWithoutBlockingTheRest() throws Exception {
        insertDue(2);
        Producer producer = mock(Producer.class);
        when(producer.send(any(Message.class)))
                .thenThrow(new org.apache.rocketmq.client.java.exception.BadRequestException(40000, "r", "illegal"))
                .thenReturn(null);
        OutboxRelay relay =
                new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), producer, transactionTemplate);

        assertThat(relay.relayBatch(10)).isEqualTo(2);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox WHERE status = 2", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox WHERE status = 1", Integer.class))
                .isEqualTo(1);
    }

    /**
     * 重投（真实 MySQL 上的 SQL）：30 小时前创建、已置失败的记录，批量重投只动失败的那几条并遵守上限；
     * 重投后 Broker 仍然故障也不会立刻又置失败（计时从重投时刻算，created_at 不变）；Broker 恢复后被中继发出。
     */
    @Test
    void redrivenFailedRecordsAreSentAgainAndGetAFreshGiveUpWindow() throws Exception {
        insertDue(4);
        jdbc.update("UPDATE t_outbox SET created_at = NOW(3) - INTERVAL 30 HOUR, retry_count = 40,"
                + " next_retry_at = NOW(3) - INTERVAL 6 HOUR");
        jdbc.update("UPDATE t_outbox SET status = 2 WHERE msg_key IN ('K0', 'K1', 'K2')"); // K3 留作待发送
        jdbc.update("UPDATE t_outbox SET status = 1 WHERE msg_key = 'K3'"); // 改成已发送：重投不能把它放回去

        // 上限 2：只重投最老的 2 条，还剩 1 条
        OutboxRedriveResult first = outboxAdminService.redriveBatch("order-paid", 2, 10009L);
        assertThat(first.redriven()).isEqualTo(2);
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox WHERE status = 0", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox WHERE status = 1", Integer.class))
                .isEqualTo(1);

        // Broker 还没好：重投的两条发送失败，但没有因为「创建已超过 24 小时」马上又被置失败
        Producer down = mock(Producer.class);
        when(down.send(any(Message.class))).thenThrow(new ClientException("down"));
        new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), down, transactionTemplate).relayBatch(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox WHERE status = 2", Integer.class))
                .isEqualTo(1); // 仍然只有没重投的那一条
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE created_at > NOW(3) - INTERVAL 29 HOUR", Integer.class))
                .isZero(); // created_at 没被改

        // Broker 恢复：把剩下的一条也重投，三条都被发出，已发送的 K3 没有重发
        assertThat(outboxAdminService.redriveBatch(null, null, 10009L).redriven())
                .isEqualTo(1);
        jdbc.update("UPDATE t_outbox SET next_retry_at = NOW(3) - INTERVAL 1 SECOND WHERE status = 0");
        ConcurrentLinkedQueue<String> sentKeys = new ConcurrentLinkedQueue<>();
        Producer up = mock(Producer.class);
        when(up.send(any(Message.class))).thenAnswer(inv -> {
            sentKeys.add(inv.<Message>getArgument(0).getKeys().iterator().next());
            return null;
        });
        new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), up, transactionTemplate).relayBatch(10);

        assertThat(sentKeys).containsExactlyInAnyOrder("K0", "K1", "K2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_outbox WHERE status = 1", Integer.class))
                .isEqualTo(4);

        // 距重投超过 24 小时仍发不出去的，照样会被放弃：计时只是换了起点，没有被取消
        jdbc.update("UPDATE t_outbox SET status = 0, retry_since = NOW(3) - INTERVAL 25 HOUR,"
                + " next_retry_at = NOW(3) - INTERVAL 1 SECOND WHERE msg_key = 'K0'");
        new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), down, transactionTemplate).relayBatch(10);
        assertThat(jdbc.queryForObject("SELECT status FROM t_outbox WHERE msg_key = 'K0'", Integer.class))
                .isEqualTo(2);
    }
}
