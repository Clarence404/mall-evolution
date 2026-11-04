package io.github.clarence404.mall.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 让 Trace 穿过本地消息表：写 outbox 时存下 traceparent，中继在另一个线程投递时恢复；定时消息不存。 */
class OutboxTracePropagationTest {

    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    static final SpanContext REQUEST_SPAN =
            SpanContext.create(TRACE_ID, "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());

    OutboxMapper mapper = mock(OutboxMapper.class);
    OutboxWriter writer =
            new OutboxWriter(mapper, Runnable::run, new StaticListableBeanFactory().getBeanProvider(OutboxRelay.class));

    @BeforeEach
    void startSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void writerStoresCurrentTraceparentForImmediateMessages() {
        try (Scope ignored = Context.current().with(Span.wrap(REQUEST_SPAN)).makeCurrent()) {
            writer.append("order-paid", "1001", new Payload("1001"), null);
        }
        ArgumentCaptor<OutboxRecord> saved = ArgumentCaptor.forClass(OutboxRecord.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().traceParent()).isEqualTo("00-" + TRACE_ID + "-b7ad6b7169203331-01");
    }

    @Test
    void scheduledMessagesDoNotCarryTheTrace() {
        try (Scope ignored = Context.current().with(Span.wrap(REQUEST_SPAN)).makeCurrent()) {
            writer.append(
                    "order-timeout",
                    "1001",
                    new Payload("1001"),
                    LocalDateTime.now().plusMinutes(15));
        }
        ArgumentCaptor<OutboxRecord> saved = ArgumentCaptor.forClass(OutboxRecord.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().traceParent()).isNull(); // 免得一条 Trace 拖 15 分钟
    }

    @Test
    void relaySendsUnderTheOriginalTrace() throws Exception {
        Producer producer = mock(Producer.class);
        AtomicReference<String> traceIdWhileSending = new AtomicReference<>();
        when(producer.send(any(Message.class))).thenAnswer(inv -> {
            traceIdWhileSending.set(Span.current().getSpanContext().getTraceId());
            return null;
        });
        OutboxRelay relay =
                new OutboxRelay(mapper, ClientServiceProvider.loadService(), producer, mock(TransactionTemplate.class));
        OutboxRecord r = OutboxRecord.of("order-paid", "1001", "{}", null);
        r.setTraceParent("00-" + TRACE_ID + "-b7ad6b7169203331-01");

        relay.send(r); // 在中继线程上，当前没有任何 Trace

        assertThat(traceIdWhileSending.get()).isEqualTo(TRACE_ID);
        assertThat(Span.current().getSpanContext().isValid()).isFalse(); // 发完恢复原上下文
    }

    record Payload(String orderNo) {}
}
