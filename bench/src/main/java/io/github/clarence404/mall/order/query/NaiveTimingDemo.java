package io.github.clarence404.mall.order.query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 反面教材：不用 JMH，自己用 System.nanoTime() 套个循环去量 {@link OrderCursor#decode}，几种常见写法各量出多少。
 * 和 {@link OrderCursorBenchmark} 的结果放在一起看，就知道手写计时差在哪。
 *
 * <pre>
 * 第 1 次调用          类加载、正则编译、解释执行都算在里面
 * 前 1000 次的平均     还在解释执行和 C1 阶段
 * 预热后，结果不用     循环 100 万次，返回值直接丢掉——JIT 有可能把整段计算当成死代码削掉一部分
 * 预热后，结果累加     循环 100 万次，返回值加到一个最后会用到的变量上
 * </pre>
 *
 * <p>用法：java -cp ... io.github.clarence404.mall.order.query.NaiveTimingDemo 输出文件。每种写法连量 5 遍，都写进输出文件。
 */
public final class NaiveTimingDemo {

    private NaiveTimingDemo() {}

    public static void main(String[] args) throws IOException {
        String cursor = OrderCursor.encode(1_987_654_321_098_765_432L);
        List<String> lines = new ArrayList<>();
        lines.add("case,round,ns_per_call");

        long t0 = System.nanoTime();
        OrderCursor.decode(cursor);
        lines.add("第 1 次调用,1," + (System.nanoTime() - t0));

        t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            OrderCursor.decode(cursor);
        }
        lines.add("前 1000 次的平均,1," + (System.nanoTime() - t0) / 1000);

        for (int i = 0; i < 200_000; i++) { // 预热
            OrderCursor.decode(cursor);
        }
        int n = 1_000_000;
        for (int round = 1; round <= 5; round++) {
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                OrderCursor.decode(cursor);
            }
            lines.add("预热后，结果不用," + round + "," + (System.nanoTime() - t0) / n);
        }
        long sum = 0;
        for (int round = 1; round <= 5; round++) {
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sum += OrderCursor.decode(cursor);
            }
            lines.add("预热后，结果累加," + round + "," + (System.nanoTime() - t0) / n);
        }
        lines.add("校验和（保证累加的结果真的被用到）,0," + sum);
        Files.write(Path.of(args[0]), lines, StandardCharsets.UTF_8);
    }
}
