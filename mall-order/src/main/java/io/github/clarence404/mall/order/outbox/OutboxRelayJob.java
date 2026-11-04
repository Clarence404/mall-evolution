package io.github.clarence404.mall.order.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫表兜底：每秒一次。事务与加锁查询在 {@link OutboxRelay} 里，调度 Bean 单独拆出来。 */
@Component
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
class OutboxRelayJob {

    private final OutboxRelay outboxRelay;

    @Scheduled(fixedDelayString = "${mall.outbox.relay-interval:1s}")
    void relay() {
        int sent;
        do {
            sent = outboxRelay.relayBatch(100);
        } while (sent == 100); // 积压时连续处理，直到不足一批
    }
}
