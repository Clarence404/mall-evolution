package io.github.clarence404.mall.common.id;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * 雪花 ID 的发号速度：{@link SnowflakeIdGenerator#nextId} 是一个 synchronized 方法，1 个线程、8 个线程一起调各能发多少。
 *
 * <p>看的不是「锁慢不慢」，而是这个算法自己的上限：序列号 12 位，一毫秒最多发 4096 个，发完就原地等到下一毫秒，
 * 所以每秒最多约 410 万个，和几个线程、锁怎么实现都没有关系。下一单用 4 个 ID（订单主键、订单号、明细、outbox），
 * 压测里下单最高 400 单/秒，每秒 1600 个 ID，离这个上限差三个数量级。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class SnowflakeBenchmark {

    private final SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);

    @Benchmark
    @Threads(1)
    public long oneThread() {
        return generator.nextId();
    }

    @Benchmark
    @Threads(8)
    public long eightThreads() {
        return generator.nextId();
    }
}
