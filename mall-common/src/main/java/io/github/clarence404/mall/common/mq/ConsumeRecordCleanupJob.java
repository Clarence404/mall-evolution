package io.github.clarence404.mall.common.mq;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 去重记录保留 30 天，每天凌晨分批删除。 */
@Slf4j
@Component
@RequiredArgsConstructor
class ConsumeRecordCleanupJob {

    private final ConsumeRecordMapper consumeRecordMapper;

    @Scheduled(cron = "${mall.mq.consume-record-cleanup-cron:0 30 3 * * *}")
    void cleanup() {
        LocalDateTime before = LocalDateTime.now().minusDays(30);
        int total = 0;
        int deleted;
        do {
            deleted = consumeRecordMapper.deleteBefore(before, 1000);
            total += deleted;
        } while (deleted == 1000);
        log.info("清理 30 天前的消费去重记录 {} 条", total);
    }
}
