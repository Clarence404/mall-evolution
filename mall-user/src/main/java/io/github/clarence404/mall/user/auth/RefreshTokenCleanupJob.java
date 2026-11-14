package io.github.clarence404.mall.user.auth;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天凌晨清理过期的刷新令牌。
 *
 * <p>刷新令牌只在「刷新时轮换」「登出」「踢下线」时删除；用户登录后不再回来（或流量模拟器这类长期登录的客户端每次都重新登录）
 * 留下的令牌到期后没人删，表会一直涨。过期令牌已经不能用了，这里只是回收空间。
 *
 * <p>多实例同时执行无害：就是一条按 expire_at 的 DELETE，各删各的批次，重复执行的结果相同，不需要抢占或加分布式锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    static final int BATCH = 1000;

    private final RefreshTokenMapper refreshTokenMapper;

    /** 默认每天 03:45，和 outbox（03:00）、消费去重记录（03:30）的清理错开。 */
    @Scheduled(cron = "${mall.auth.refresh-token-cleanup-cron:0 45 3 * * *}")
    public int cleanup() {
        LocalDateTime now = LocalDateTime.now();
        int total = 0;
        int deleted;
        do { // 分批删：每批一个短事务，不长时间持有大量行锁
            deleted = refreshTokenMapper.deleteExpiredBefore(now, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        log.info("清理过期的刷新令牌 {} 条", total);
        return total;
    }
}
