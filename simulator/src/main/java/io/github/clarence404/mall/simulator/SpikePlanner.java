package io.github.clarence404.mall.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * 决定下一次突发高峰什么时候来：间隔服从指数分布（均值 mean-interval），也就是「平均 6 小时一次，但说不准哪一刻」，和真实的突发一样没有固定节奏；
 * 每次至少隔 first-after（启动后的第一次同样如此），不会一启动就打满。
 */
public final class SpikePlanner {

    private final Duration minGap;
    private final Duration meanInterval;

    /**
     * 创建。
     *
     * @param minGap 最短间隔（也是启动后第一次的最早时间）
     * @param meanInterval 平均间隔
     */
    public SpikePlanner(Duration minGap, Duration meanInterval) {
        this.minGap = minGap;
        this.meanInterval = meanInterval;
    }

    /**
     * 从某个时刻起，下一次高峰的开始时间。
     *
     * @param from 起点（启动时刻，或上一次高峰结束的时刻）
     * @param random 随机源
     * @return 开始时间，至少在 from + minGap 之后
     */
    public Instant next(Instant from, RandomGenerator random) {
        // 指数分布的反函数采样：-ln(U) × 均值，U 在 (0,1]
        double u = 1.0 - random.nextDouble();
        long millis = (long) (-Math.log(u) * meanInterval.toMillis());
        return from.plus(Duration.ofMillis(Math.max(millis, minGap.toMillis())));
    }
}
