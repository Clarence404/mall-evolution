package io.github.clarence404.mall.common.outbox;

import io.github.clarence404.mall.common.json.Json;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 写本地消息表：与业务数据同一个事务写入，提交后立刻交给中继发送，不等扫表。
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxMapper outboxMapper;

    @Qualifier("outboxExecutor")
    private final Executor outboxExecutor;

    /** RocketMQ 未启用时没有中继（如单元与 H2 测试），记录照常写入，恢复后由扫表补发。 */
    private final ObjectProvider<OutboxRelay> outboxRelay;

    @Transactional(propagation = Propagation.MANDATORY) // 必须在业务事务内调用
    public void append(String topic, String orderNo, Object payload, LocalDateTime deliverAt) {
        OutboxRecord r = OutboxRecord.of(topic, orderNo, Json.write(payload), deliverAt);
        outboxMapper.insert(r);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { // 提交后立即发，不等扫表
                outboxRelay.ifAvailable(relay -> outboxExecutor.execute(() -> relay.relayOne(r.id())));
            }
        });
    }
}
