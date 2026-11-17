package io.github.clarence404.mall.perf.ha;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高可用实验的参数：一律从 JVM 系统属性读（{@code -Dha.xxx=...}，由 experiments/ 下的脚本传入），读到的值连同默认值都记下来，
 * 压测结束时写进结果目录的 params.csv——事后对数据时能确切知道这一轮用的是什么参数。
 */
public final class HaConfig {

    private final String simulation;
    private final Map<String, String> used = new LinkedHashMap<>();
    private Path outDir;

    public HaConfig(String simulation) {
        this.simulation = simulation;
    }

    public String str(String key, String defaultValue) {
        String v = System.getProperty(key, defaultValue);
        used.put(key, v);
        return v;
    }

    public int integer(String key, int defaultValue) {
        return Integer.parseInt(str(key, String.valueOf(defaultValue)).trim());
    }

    public long longValue(String key, long defaultValue) {
        return Long.parseLong(str(key, String.valueOf(defaultValue)).trim());
    }

    public double dbl(String key, double defaultValue) {
        return Double.parseDouble(str(key, String.valueOf(defaultValue)).trim());
    }

    public boolean bool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(str(key, String.valueOf(defaultValue)).trim());
    }

    public List<String> list(String key, String defaultValue) {
        return Arrays.stream(str(key, defaultValue).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 必填参数：没传就让压测直接失败，并说明去哪里拿。 */
    public String required(String key, String hint) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少参数 -D" + key + "：" + hint);
        }
        used.put(key, "***"); // 密钥类参数不落盘
        return v;
    }

    /** 结果目录：脚本用 -Dha.out 指定；手工跑时落在 perf/target/ha-results/ 下。同一轮压测里只算一次。 */
    public synchronized Path outDir() {
        if (outDir == null) {
            String dir = System.getProperty("ha.out");
            if (dir == null || dir.isBlank()) {
                dir = "target/ha-results/" + simulation + "-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            }
            used.put("ha.out", dir);
            outDir = Path.of(dir);
        }
        return outDir;
    }

    /**
     * 压测真正开始注入流量前调用（Simulation.before）：在结果目录写 started.txt（epoch 毫秒）。实验脚本等这个文件出现，
     * 才开始按时间点做事（抓线程栈、逐个重启实例），不受 Maven 启动、编译耗时的影响。
     */
    public void markStarted() {
        try {
            Files.createDirectories(outDir());
            Files.writeString(outDir().resolve("started.txt"), String.valueOf(System.currentTimeMillis()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, String> used() {
        return Map.copyOf(used);
    }

    /** params.csv 的内容：按读取顺序。 */
    public List<String> usedAsCsvLines() {
        return used.entrySet().stream()
                .map(e -> Timeline.csv(e.getKey()) + "," + Timeline.csv(e.getValue()))
                .toList();
    }
}
