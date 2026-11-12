package io.github.clarence404.mall.simulator;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 统计：每个计数各有「本周期」和「累计」两份，每分钟打印一行后把本周期清零。多个虚拟用户线程同时加，用 LongAdder（高并发下比 AtomicLong 争用小）。
 */
public final class Stats {

    /** 计数项。 */
    public enum Counter {
        /** 发出的 HTTP 请求。 */
        REQUESTS,
        /** 2xx。 */
        S2XX,
        /** 4xx（不含 429）。 */
        S4XX,
        /** 429 限流。 */
        S429,
        /** 5xx。 */
        S5XX,
        /** 连不上、超时。 */
        IO_ERRORS,
        /** 登录成功。 */
        LOGINS,
        /** 下单成功（201，不含重复提交的重放）。 */
        ORDERS,
        /** 支付回调成功。 */
        PAID,
        /** 主动取消。 */
        CANCELLED,
        /** 不付款、等超时关单。 */
        UNPAID,
        /** 重复提交（同一个幂等键）。 */
        DUPLICATE_SUBMITS,
        /** 下单被拒（库存不足、待支付太多等 409）。 */
        ORDER_REJECTED,
        /** 故意制造的异常请求。 */
        ANOMALIES,
        /** 因库存保护或每小时上限没下单。 */
        ORDER_SKIPPED,
        /** 补货次数。 */
        RESTOCKS
    }

    private final Map<Counter, LongAdder> interval = new EnumMap<>(Counter.class);
    private final Map<Counter, LongAdder> total = new EnumMap<>(Counter.class);

    /** 创建，全部计数为 0。 */
    public Stats() {
        for (Counter c : Counter.values()) {
            interval.put(c, new LongAdder());
            total.put(c, new LongAdder());
        }
    }

    /**
     * 加一。
     *
     * @param counter 计数项
     */
    public void inc(Counter counter) {
        interval.get(counter).increment();
        total.get(counter).increment();
    }

    /**
     * 记一次 HTTP 响应：按状态码归类。
     *
     * @param status 状态码，-1 表示连不上或超时
     */
    public void response(int status) {
        inc(Counter.REQUESTS);
        if (status < 0) {
            inc(Counter.IO_ERRORS);
        } else if (status == 429) {
            inc(Counter.S429);
        } else if (status >= 500) {
            inc(Counter.S5XX);
        } else if (status >= 400) {
            inc(Counter.S4XX);
        } else {
            inc(Counter.S2XX);
        }
    }

    /**
     * 取出本周期的计数并清零。
     *
     * @return 计数项 → 本周期的值
     */
    public Map<Counter, Long> drainInterval() {
        Map<Counter, Long> out = new EnumMap<>(Counter.class);
        interval.forEach((c, adder) -> out.put(c, adder.sumThenReset()));
        return out;
    }

    /**
     * 某一项的累计值。
     *
     * @param counter 计数项
     * @return 启动以来的合计
     */
    public long total(Counter counter) {
        return total.get(counter).sum();
    }
}
