package io.github.clarence404.mall.order.query;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * 订单列表游标的编解码：{@link OrderCursor} 现在的写法（拼一段 JSON 再 Base64，解码时用正则取出 id），
 * 和「直接把 id 写成十进制字符串」的最简写法各要多久、各分配多少内存。
 *
 * <p>每查一页订单列表做一次解码、有下一页时做一次编码。这组基准回答两个问题：现在的写法慢多少，以及这点差别放到一次
 * 列表请求里占多大比例。
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class OrderCursorBenchmark {

    private long id = 1_987_654_321_098_765_432L;
    private String cursor = OrderCursor.encode(id);
    private String plain = Long.toString(id);

    @Benchmark
    public String encodeJsonBase64() {
        return OrderCursor.encode(id);
    }

    @Benchmark
    public Long decodeJsonBase64() {
        return OrderCursor.decode(cursor);
    }

    /** 对照：游标就是 id 的十进制字符串。 */
    @Benchmark
    public String encodePlain() {
        return Long.toString(id);
    }

    @Benchmark
    public long decodePlain() {
        return Long.parseLong(plain);
    }
}
