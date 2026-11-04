package io.github.clarence404.mall.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayTest {

    OutboxMapper mapper = mock(OutboxMapper.class);
    Producer producer = mock(Producer.class);
    RecordingTransactionTemplate tx = new RecordingTransactionTemplate();
    OutboxRelay relay = new OutboxRelay(mapper, ClientServiceProvider.loadService(), producer, tx);

    /** 只记录「当前是否在事务回调里」，用来断言发送发生在事务之外。 */
    static class RecordingTransactionTemplate extends TransactionTemplate {
        boolean inTransaction;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            inTransaction = true;
            try {
                return action.doInTransaction(new SimpleTransactionStatus());
            } finally {
                inTransaction = false;
            }
        }
    }

    @Test
    void sendsWithKeysAndMarksSent() throws Exception {
        OutboxRecord r = OutboxRecord.of("order-paid", "1001", "{\"orderNo\":\"1001\"}", null);

        relay.send(r);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(sent.capture());
        assertThat(sent.getValue().getTopic()).isEqualTo("order-paid");
        assertThat(sent.getValue().getKeys()).containsExactly("1001");
        assertThat(StandardCharsets.UTF_8.decode(sent.getValue().getBody()).toString())
                .contains("1001");
        assertThat(sent.getValue().getDeliveryTimestamp()).isEmpty();
        verify(mapper).markSent(r.id());
    }

    @Test
    void scheduledRecordBecomesRocketMqTimedMessage() throws Exception {
        LocalDateTime deliverAt = LocalDateTime.now().plusMinutes(15);
        OutboxRecord r = OutboxRecord.of("order-timeout", "1001", "{\"orderNo\":\"1001\"}", deliverAt);

        relay.send(r);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(sent.capture());
        assertThat(sent.getValue().getDeliveryTimestamp())
                .contains(deliverAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    @Test
    void brokerFailureSchedulesRetryInsteadOfMarkingSent() throws Exception {
        when(producer.send(any(Message.class))).thenThrow(new ClientException("broker down"));
        OutboxRecord r = OutboxRecord.of("order-paid", "1001", "{}", null);

        relay.send(r);

        verify(mapper).markRetry(r.id());
        verify(mapper, never()).markSent(anyLong());
    }

    @Test
    void batchIsClaimedWithLeaseInShortTransactionAndSentOutsideIt() throws Exception {
        OutboxRecord r1 = OutboxRecord.of("order-paid", "1001", "{}", null);
        OutboxRecord r2 = OutboxRecord.of("order-paid", "1002", "{}", null);
        when(mapper.selectDueForUpdate(100)).thenReturn(List.of(r1, r2));
        List<Boolean> sendInsideTx = new ArrayList<>();
        when(producer.send(any(Message.class))).thenAnswer(inv -> {
            sendInsideTx.add(tx.inTransaction);
            return null;
        });

        assertThat(relay.relayBatch(100)).isEqualTo(2);

        verify(mapper).lease(List.of(r1.id(), r2.id()), OutboxRelay.LEASE_SECONDS);
        assertThat(sendInsideTx).containsExactly(false, false); // 发送时事务已提交，不持有行锁与连接
        verify(mapper).markSent(r1.id());
        verify(mapper).markSent(r2.id());
    }

    @Test
    void brokerFailureStopsBatchAndReleasesRemainingLeases() throws Exception {
        OutboxRecord r1 = OutboxRecord.of("order-paid", "1001", "{}", null);
        OutboxRecord r2 = OutboxRecord.of("order-paid", "1002", "{}", null);
        OutboxRecord r3 = OutboxRecord.of("order-paid", "1003", "{}", null);
        when(mapper.selectDueForUpdate(100)).thenReturn(List.of(r1, r2, r3));
        when(producer.send(any(Message.class))).thenThrow(new ClientException("broker down"));

        assertThat(relay.relayBatch(100)).isZero();

        verify(producer, times(1)).send(any(Message.class)); // 第一条超时后不再逐条撞
        verify(mapper).markRetry(r1.id());
        verify(mapper).releaseLease(List.of(r2.id(), r3.id()));
        verify(mapper, never()).markSent(anyLong());
    }

    @Test
    void lockConflictWhileClaimingSkipsThisRoundWithoutSending() throws Exception {
        when(mapper.selectDueForUpdate(100)).thenThrow(new DeadlockLoserDataAccessException("deadlock", null));

        assertThat(relay.relayBatch(100)).isZero();

        verify(producer, never()).send(any(Message.class));
    }

    @Test
    void claimTransactionUsesReadCommitted() {
        TransactionTemplate claim = OutboxRelay.readCommitted(
                new TransactionTemplate(mock(org.springframework.transaction.PlatformTransactionManager.class)));

        assertThat(claim.getIsolationLevel())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void relayOneSkipsRecordAlreadyClaimed() throws Exception {
        when(mapper.selectPendingForUpdate(42L)).thenReturn(null);

        relay.relayOne(42L);

        verify(producer, never()).send(any(Message.class));
        verify(mapper, never()).lease(any(), anyInt());
    }

    @Test
    void newRecordWaitsThreeSecondsBeforeScanPicksItUp() {
        OutboxRecord r = OutboxRecord.of("order-paid", "1001", "{}", null);
        assertThat(r.nextRetryAt()).isAfter(LocalDateTime.now().plusSeconds(2));
        assertThat(r.status()).isEqualTo(OutboxRecord.PENDING);
    }
}
