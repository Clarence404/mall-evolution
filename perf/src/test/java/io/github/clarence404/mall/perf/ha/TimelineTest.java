package io.github.clarence404.mall.perf.ha;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 按秒统计：同一秒、同一请求、同一状态码合并成一行，百分位按最近秩取；事件与参数各写一份文件。 */
class TimelineTest {

    @TempDir
    Path dir;

    @Test
    void aggregatesPerSecondRequestAndStatus() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000_000L); // epoch 1000000000 秒整
        Timeline t = new Timeline(now::get);
        for (int i = 1; i <= 100; i++) {
            t.record("GET /a", 200, i);
        }
        t.record("GET /a", 0, 3000); // 没拿到响应
        now.addAndGet(1_500);
        t.record("GET /a", 503, 7);
        t.mark("注入：下游变慢, 5000ms");

        t.write(dir, List.of("ha.orderRps,40"));

        List<String> lines = Files.readAllLines(dir.resolve("timeline.csv"), StandardCharsets.UTF_8);
        assertThat(lines)
                .containsExactly(
                        "epoch_s,t_s,request,status,count,p50_ms,p95_ms,p99_ms,max_ms",
                        "1000000000,0,GET /a,0,1,3000,3000,3000,3000",
                        "1000000000,0,GET /a,200,100,50,95,99,100",
                        "1000000001,1,GET /a,503,1,7,7,7,7");
        assertThat(Files.readAllLines(dir.resolve("events.csv"), StandardCharsets.UTF_8))
                .containsExactly("epoch_s,t_s,event", "1000000001,1,\"注入：下游变慢, 5000ms\"");
        assertThat(Files.readAllLines(dir.resolve("params.csv"), StandardCharsets.UTF_8))
                .containsExactly("key,value", "ha.orderRps,40");
    }

    @Test
    void writesWholeRunLatencyInMicros() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000_000_000L);
        Timeline t = new Timeline(now::get);
        for (int i = 1; i <= 1000; i++) {
            now.addAndGet(7); // 跨了好几秒：整场的分位数不按秒分
            t.recordMicros("GET /a", 200, i * 10L);
        }
        t.recordMicros("GET /a", 503, 1_999);

        t.write(dir, List.of());

        assertThat(Files.readAllLines(dir.resolve("latency.csv"), StandardCharsets.UTF_8))
                .containsExactly(
                        "request,status,count,mean_us,p50_us,p90_us,p99_us,p999_us,p9999_us,max_us",
                        "GET /a,200,1000,5005,5000,9000,9900,9990,10000,10000",
                        "GET /a,503,1,1999,1999,1999,1999,1999,1999,1999");
        // 逐秒的那份仍是整毫秒：1999 微秒记成 1
        assertThat(Files.readAllLines(dir.resolve("timeline.csv"), StandardCharsets.UTF_8))
                .anyMatch(l -> l.endsWith("GET /a,503,1,1,1,1,1"));
    }

    @Test
    void percentileUsesNearestRank() {
        long[] sorted = {10, 20, 30, 40};

        assertThat(Timeline.percentile(sorted, 50)).isEqualTo(20);
        assertThat(Timeline.percentile(sorted, 99)).isEqualTo(40);
        assertThat(Timeline.percentile(new long[] {5}, 99)).isEqualTo(5);
    }
}
