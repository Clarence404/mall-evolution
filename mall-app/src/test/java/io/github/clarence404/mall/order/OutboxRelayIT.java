package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.order.outbox.OutboxMapper;
import io.github.clarence404.mall.order.outbox.OutboxRelay;
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
 * 中继在真实 MySQL 上的行为：两个实例同时扫表，FOR UPDATE SKIP LOCKED 保证每条只发一次；Broker 失败按退避重试。
 * Producer 用替身（Testcontainers 没有 RocketMQ 模块）。需要 Docker。
 */
class OutboxRelayIT extends IntegrationTestBase {

    @Autowired
    OutboxMapper outboxMapper;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbc;

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

    @Test
    void brokerFailureBacksOffAndEventuallyFails() throws Exception {
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

        jdbc.update("UPDATE t_outbox SET retry_count = 9, next_retry_at = NOW(3) - INTERVAL 1 SECOND");
        relay.relayBatch(10);
        assertThat(jdbc.queryForObject("SELECT status FROM t_outbox", Integer.class))
                .isEqualTo(2);
    }
}
