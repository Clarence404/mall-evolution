package io.github.clarence404.mall.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地消息表的两个周期任务与监控：
 *
 * <ul>
 *   <li>{@code outbox.pending.age}：最老一条待发送记录等了多久，超过 1 分钟告警，涨说明中继或 Broker 出了问题
 *   <li>{@code outbox.failed.count}：失败记录数，大于 0 告警
 *   <li>已发送的记录保留 7 天，每天凌晨分批删除
 * </ul>
 */
@Slf4j
@Component
class OutboxJobs {

    private final OutboxMapper outboxMapper;
    private final AtomicLong pendingAgeSeconds = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    OutboxJobs(OutboxMapper outboxMapper, MeterRegistry registry) {
        this.outboxMapper = outboxMapper;
        Gauge.builder("outbox.pending.age", pendingAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .description("t_outbox 最老一条待发送记录的等待时长")
                .register(registry);
        Gauge.builder("outbox.failed.count", failedCount, AtomicLong::get)
                .description("t_outbox 失败记录数")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${mall.outbox.metrics-interval:15s}")
    void refreshMetrics() {
        LocalDateTime oldest = outboxMapper.selectOldestPendingCreatedAt();
        pendingAgeSeconds.set(
                oldest == null
                        ? 0
                        : Math.max(
                                0, Duration.between(oldest, LocalDateTime.now()).toSeconds()));
        failedCount.set(outboxMapper.countFailed());
    }

    @Scheduled(cron = "${mall.outbox.cleanup-cron:0 0 3 * * *}")
    void cleanupSent() {
        LocalDateTime before = LocalDateTime.now().minusDays(7);
        int total = 0;
        int deleted;
        do {
            deleted = outboxMapper.deleteSentBefore(before, 1000);
            total += deleted;
        } while (deleted == 1000);
        log.info("清理 7 天前已发送的 outbox 记录 {} 条", total);
    }
}
