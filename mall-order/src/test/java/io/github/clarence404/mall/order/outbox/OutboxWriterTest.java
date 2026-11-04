package io.github.clarence404.mall.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OutboxWriterTest {

    OutboxMapper mapper = mock(OutboxMapper.class);
    OutboxRelay relay = mock(OutboxRelay.class);
    List<Runnable> submitted = new ArrayList<>();

    @BeforeEach
    void startSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void insertsInsideTransactionAndRelaysOnlyAfterCommit() {
        OutboxWriter writer = new OutboxWriter(mapper, submitted::add, provider(relay));

        writer.append("order-paid", "1001", new Payload("1001"), null);

        ArgumentCaptor<OutboxRecord> inserted = ArgumentCaptor.forClass(OutboxRecord.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().payload()).isEqualTo("{\"orderNo\":\"1001\"}");
        assertThat(submitted).isEmpty(); // 提交前不发

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(submitted).hasSize(1);
        submitted.getFirst().run();
        verify(relay).relayOne(inserted.getValue().id());
    }

    @Test
    void withoutRelayRecordStaysForScan() {
        OutboxWriter writer = new OutboxWriter(mapper, submitted::add, provider(null));

        writer.append("order-paid", "1001", new Payload("1001"), null);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(submitted).isEmpty();
        verify(relay, never()).relayOne(anyLong());
    }

    record Payload(String orderNo) {}

    private static ObjectProvider<OutboxRelay> provider(OutboxRelay relay) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (relay != null) {
            factory.addBean("outboxRelay", relay);
        }
        return factory.getBeanProvider(OutboxRelay.class);
    }
}
