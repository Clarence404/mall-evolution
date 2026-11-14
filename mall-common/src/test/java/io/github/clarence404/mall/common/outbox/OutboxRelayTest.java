package io.github.clarence404.mall.common.outbox;

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
import org.apache.rocketmq.client.java.exception.BadRequestException;
import org.apache.rocketmq.client.java.exception.NotFoundException;
import org.apache.rocketmq.client.java.exception.PayloadTooLargeException;
import org.apache.rocketmq.client.java.exception.TooManyRequestsException;
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

        verify(mapper).markRetry(r.id(), 1L, 86400L); // 第 1 次失败：1 秒后再试，24 小时内不放弃
        verify(mapper, never()).markSent(anyLong());
        verify(mapper, never()).markFailed(anyLong()); // Broker 故障不是消息的错，不置失败
    }

    /** 退避 1、2、4…256 秒翻倍后封顶 240 秒：失败多少次都是最久 4 分钟试一次，不再有「第 10 次就放弃」。 */
    @Test
    void backoffDoublesAndIsCappedAtFourMinutes() {
        assertThat(OutboxRelay.backoffSeconds(0)).isEqualTo(1);
        assertThat(OutboxRelay.backoffSeconds(3)).isEqualTo(8);
        assertThat(OutboxRelay.backoffSeconds(7)).isEqualTo(128);
        assertThat(OutboxRelay.backoffSeconds(8)).isEqualTo(240);
        assertThat(OutboxRelay.backoffSeconds(9)).isEqualTo(240);
        assertThat(OutboxRelay.backoffSeconds(500)).isEqualTo(240);
    }

    /** 放弃时限可配置（mall.outbox.give-up-after），原样传给回写的 SQL。 */
    @Test
    void giveUpAfterIsPassedToTheRetryUpdate() throws Exception {
        OutboxRelay oneHour = new OutboxRelay(
                mapper, ClientServiceProvider.loadService(), producer, tx, java.time.Duration.ofHours(1));
        when(producer.send(any(Message.class))).thenThrow(new ClientException("broker down"));
        OutboxRecord r = OutboxRecord.of("order-paid", "1001", "{}", null);

        oneHour.send(r);

        verify(mapper).markRetry(r.id(), 1L, 3600L);
    }

    /** Broker 明确拒收（消息本身非法）：立即置为失败、不再重试，而且不影响本批后面的记录。 */
    @Test
    void illegalMessageIsMarkedFailedImmediatelyAndTheBatchContinues() throws Exception {
        OutboxRecord bad = OutboxRecord.of("order-paid", "1001", "{}", null);
        OutboxRecord good = OutboxRecord.of("order-paid", "1002", "{}", null);
        when(mapper.selectDueForUpdate(100)).thenReturn(List.of(bad, good));
        when(producer.send(any(Message.class)))
                .thenThrow(new BadRequestException(40000, "req-1", "illegal message"))
                .thenReturn(null);

        assertThat(relay.relayBatch(100)).isEqualTo(2);

        verify(mapper).markFailed(bad.id());
        verify(mapper).markSent(good.id());
        verify(mapper, never()).markRetry(anyLong(), anyLong(), anyLong());
        verify(mapper, never()).releaseLease(any());
    }

    @Test
    void onlyRejectionsOfTheMessageItselfCountAsIllegal() {
        assertThat(OutboxRelay.isIllegalMessage(new BadRequestException(40000, "r", "bad")))
                .isTrue();
        assertThat(OutboxRelay.isIllegalMessage(new PayloadTooLargeException(41300, "r", "too large")))
                .isTrue();
        assertThat(OutboxRelay.isIllegalMessage(new ClientException("timeout"))).isFalse();
        assertThat(OutboxRelay.isIllegalMessage(new NotFoundException(40400, "r", "topic not found")))
                .isFalse(); // Topic 还没建：环境问题，建好后重试能成功
        assertThat(OutboxRelay.isIllegalMessage(new TooManyRequestsException(42900, "r", "flow control")))
                .isFalse();
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
        verify(mapper).markRetry(r1.id(), 1L, 86400L);
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
