package io.github.clarence404.mall.common.id;

/**
 * 雪花 ID：41 位毫秒时间戳 + 10 位机器号 + 12 位序列号，8 字节 BIGINT、趋势递增。
 *
 * <p>阶段一只有两台应用机，机器号直接写在配置里（1 和 2）。时钟回拨 5ms 以内等待追平，超过直接拒绝发号。
 */
public class SnowflakeIdGenerator implements IdGenerator {

    /** 2010-11-04T01:42:54.657Z，与 Twitter 原始实现一致，当前时间生成的 ID 为 19 位十进制。 */
    static final long EPOCH = 1288834974657L;

    private static final long WORKER_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    static final long MAX_WORKER_ID = ~(-1L << WORKER_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 0-" + MAX_WORKER_ID + " 之间: " + workerId);
        }
        this.workerId = workerId;
    }

    @Override
    public synchronized long nextId() {
        long now = currentTimeMillis();
        if (now < lastTimestamp) {
            long backward = lastTimestamp - now;
            if (backward > MAX_BACKWARD_MS) {
                throw new IllegalStateException("时钟回拨 " + backward + "ms，拒绝发号");
            }
            now = waitUntil(lastTimestamp);
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << (WORKER_BITS + SEQUENCE_BITS)) | (workerId << SEQUENCE_BITS) | sequence;
    }

    /** 由时间戳反推该毫秒最小的 ID，用于「只查近 N 个月 / 近 30 分钟」时给 id 加下界。 */
    public static long minIdAt(long epochMillis) {
        return Math.max(0L, epochMillis - EPOCH) << (WORKER_BITS + SEQUENCE_BITS);
    }

    private long waitUntil(long target) {
        long now = currentTimeMillis();
        while (now < target) {
            Thread.onSpinWait();
            now = currentTimeMillis();
        }
        return now;
    }

    long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
