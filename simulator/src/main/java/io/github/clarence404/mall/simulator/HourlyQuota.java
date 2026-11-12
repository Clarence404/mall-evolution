package io.github.clarence404.mall.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 每小时下单上限（滑动窗口）：不管曲线和权重怎么配，一小时内最多下这么多单，用来控制数据增长（订单、支付单、消息记录都会一直累积）。
 *
 * <p>记下最近一小时内每一单的时间，超过一小时的从队头扔掉；多个虚拟用户线程共用一个，方法加锁。
 */
public final class HourlyQuota {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final int limit;
    private final Deque<Instant> granted = new ArrayDeque<>();

    /**
     * 创建。
     *
     * @param limit 每小时上限，≤ 0 表示不限
     */
    public HourlyQuota(int limit) {
        this.limit = limit;
    }

    /**
     * 尝试拿一个名额。
     *
     * @param now 当前时刻
     * @return 拿到返回 true（同时记下这一单）
     */
    public synchronized boolean tryAcquire(Instant now) {
        if (limit <= 0) {
            return true;
        }
        Instant cutoff = now.minus(WINDOW);
        while (!granted.isEmpty() && !granted.peekFirst().isAfter(cutoff)) {
            granted.pollFirst();
        }
        if (granted.size() >= limit) {
            return false;
        }
        granted.addLast(now);
        return true;
    }
}
