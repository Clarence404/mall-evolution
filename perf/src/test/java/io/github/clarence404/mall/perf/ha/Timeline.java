package io.github.clarence404.mall.perf.ha;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

/**
 * 按秒统计每种请求的结果：第几秒、请求名、状态码 → 次数与 P50 / P95 / P99 / 最大耗时，压测结束写成 timeline.csv。
 *
 * <p>Gatling 自带的 HTML 报告给的是整场汇总和曲线图，看不出「注入故障后第 3 秒开始出 5xx、恢复后第 12 秒才回到正常」这种细节，
 * 前后对比和画图要的是逐秒的原始数据，所以自己再记一份。状态码 0 表示没拿到响应（客户端超时、连接被拒）。
 * 同时记录实验中的事件（注入故障、恢复、开降级……），写成 events.csv，两份文件用同一个时间轴（epoch 秒）对齐。
 *
 * <p>另外把每种请求（请求名 + 状态码）整场的耗时按微秒全部留下，写成 latency.csv：平均值与 P50 / P90 / P99 / P99.9 / P99.99 / 最大值。
 * 逐秒那份是整毫秒、每秒各算各的，回答不了「整场的 P99.9 是多少」「平均快了 0.3ms 没有」——高性能实验要看的正是这两样。
 * 预热阶段的请求名带后缀，天然和正式阶段分开。
 */
public final class Timeline {

    private final LongSupplier clockMillis;
    private final long startMillis;
    private final Map<Key, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<Key, Bucket> overall = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();

    public Timeline() {
        this(System::currentTimeMillis);
    }

    Timeline(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
        this.startMillis = clockMillis.getAsLong();
    }

    /** 记一次请求结果。status 为 0 表示没有响应。 */
    public void record(String request, int status, long elapsedMillis) {
        recordMicros(request, status, elapsedMillis * 1000);
    }

    /** 同上，耗时精确到微秒：逐秒的那份仍按整毫秒（向下取整）记，整场的那份按微秒记。 */
    public void recordMicros(String request, int status, long elapsedMicros) {
        long epochSecond = clockMillis.getAsLong() / 1000;
        buckets.computeIfAbsent(new Key(epochSecond, request, status), k -> new Bucket())
                .add(elapsedMicros / 1000);
        overall.computeIfAbsent(new Key(0, request, status), k -> new Bucket()).add(elapsedMicros);
    }

    /** 记一个实验事件（如「注入：库存 reserve 延迟 5000ms」）。 */
    public void mark(String event) {
        long now = clockMillis.getAsLong();
        events.add(now / 1000 + "," + (now - startMillis) / 1000 + "," + csv(event));
    }

    public void write(Path dir, List<String> params) throws IOException {
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>();
        lines.add("epoch_s,t_s,request,status,count,p50_ms,p95_ms,p99_ms,max_ms");
        long startSecond = startMillis / 1000;
        buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(Key::epochSecond)
                        .thenComparing(Key::request)
                        .thenComparingInt(Key::status)))
                .forEach(e -> {
                    long[] sorted = e.getValue().sorted();
                    Key k = e.getKey();
                    lines.add(k.epochSecond() + "," + (k.epochSecond() - startSecond) + "," + csv(k.request()) + ","
                            + k.status() + "," + sorted.length + "," + percentile(sorted, 50) + ","
                            + percentile(sorted, 95) + "," + percentile(sorted, 99) + ","
                            + sorted[sorted.length - 1]);
                });
        Files.write(dir.resolve("timeline.csv"), lines, StandardCharsets.UTF_8);

        List<String> lat = new ArrayList<>();
        lat.add("request,status,count,mean_us,p50_us,p90_us,p99_us,p999_us,p9999_us,max_us");
        overall.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Key::request).thenComparingInt(Key::status)))
                .forEach(e -> {
                    long[] sorted = e.getValue().sorted();
                    long sum = 0;
                    for (long v : sorted) {
                        sum += v;
                    }
                    Key k = e.getKey();
                    lat.add(csv(k.request()) + "," + k.status() + "," + sorted.length + "," + sum / sorted.length + ","
                            + percentile(sorted, 50.0) + "," + percentile(sorted, 90.0) + ","
                            + percentile(sorted, 99.0) + "," + percentile(sorted, 99.9) + ","
                            + percentile(sorted, 99.99) + "," + sorted[sorted.length - 1]);
                });
        Files.write(dir.resolve("latency.csv"), lat, StandardCharsets.UTF_8);

        List<String> ev = new ArrayList<>();
        ev.add("epoch_s,t_s,event");
        ev.addAll(events);
        Files.write(dir.resolve("events.csv"), ev, StandardCharsets.UTF_8);

        List<String> p = new ArrayList<>();
        p.add("key,value");
        p.addAll(params);
        Files.write(dir.resolve("params.csv"), p, StandardCharsets.UTF_8);
    }

    /** 最近秩法取百分位（与 Gatling 报告的口径一致：不插值）。 */
    static long percentile(long[] sorted, int p) {
        return percentile(sorted, (double) p);
    }

    /** 同上，百分位可以带小数（99.9、99.99）。 */
    static long percentile(long[] sorted, double p) {
        // 先乘后除，再减一个很小的数：99.9 / 100.0 * 1000 在浮点里是 999.0000000000001，直接向上取整会多取一位
        int rank = (int) Math.ceil(p * sorted.length / 100.0 - 1e-9);
        return sorted[Math.max(0, Math.min(sorted.length, rank) - 1)];
    }

    static String csv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private record Key(long epochSecond, String request, int status) {}

    /** 一秒内同一种结果的耗时样本。 */
    private static final class Bucket {
        private long[] samples = new long[16];
        private int size;

        synchronized void add(long v) {
            if (size == samples.length) {
                samples = Arrays.copyOf(samples, size * 2);
            }
            samples[size++] = v;
        }

        synchronized long[] sorted() {
            long[] copy = Arrays.copyOf(samples, size);
            Arrays.sort(copy);
            return copy;
        }
    }
}
