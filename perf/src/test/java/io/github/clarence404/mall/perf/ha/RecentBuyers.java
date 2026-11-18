package io.github.clarence404.mall.perf.ha;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntUnaryOperator;

/**
 * 最近下过单的实验用户的令牌：定长环形缓冲，写满后新的覆盖最老的。
 *
 * <p>连接池实验的「查订单列表」要查到真有订单的用户（空列表的查询太轻，不像真实流量），而实验用户每个只下一单、用完即弃；
 * 所以下单成功的用户记到这里，查列表时随机挑一个。攒够 minSize 个之前 {@link #pick} 返回 null（调用方退回用当前用户，查到空列表）：
 * 人太少时列表请求会集中在几个用户身上，撞上网关的每用户限流（每秒 20 次、突发 40）。
 */
public final class RecentBuyers {

    private final AtomicReferenceArray<String> ring;
    private final int minSize;
    private final AtomicLong added = new AtomicLong();

    public RecentBuyers(int capacity, int minSize) {
        if (capacity < 1 || minSize < 1 || minSize > capacity) {
            throw new IllegalArgumentException("要求 1 <= minSize <= capacity");
        }
        this.ring = new AtomicReferenceArray<>(capacity);
        this.minSize = minSize;
    }

    /** 记下一个刚下单成功的用户。 */
    public void add(String token) {
        long n = added.getAndIncrement();
        ring.set((int) (n % ring.length()), token);
    }

    /** 现在记着多少个用户（不超过容量）。 */
    public int size() {
        return (int) Math.min(added.get(), ring.length());
    }

    /**
     * 随机挑一个最近下过单的用户；不足 minSize 个时返回 null。
     *
     * @param random 给一个上界 n，返回 [0, n) 里的随机数（测试里换成固定值）
     */
    public String pick(IntUnaryOperator random) {
        int size = size();
        if (size < minSize) {
            return null;
        }
        return ring.get(random.applyAsInt(size)); // 并发的 add 可能刚占了号还没写进去：读到 null 时调用方同样退回当前用户
    }
}
