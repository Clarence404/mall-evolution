package io.github.clarence404.mall.perf.ha;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶梯加压的日程：从 startRps 起，每 stepSec 秒加 stepRps，加到 maxRps 为止；每一级都持续 stepSec 秒，级与级之间不设过渡。
 *
 * <p>单独拿出来是为了三件事用同一份算法：Gatling 的注入步骤、events.csv 里每一级开始的时刻、以及这一轮一共会发多少请求
 * （要用多少个不重复的实验用户、热点 SKU 要备多少库存）。
 *
 * @param levels 各级（按时间先后）
 */
public record StairSchedule(List<Level> levels) {

    /**
     * 一级阶梯。
     *
     * @param index 第几级，从 1 起
     * @param rps 这一级每秒的请求数
     * @param startSec 这一级从压测开始后的第几秒起
     * @param durationSec 持续多少秒
     */
    public record Level(int index, double rps, int startSec, int durationSec) {}

    public StairSchedule {
        levels = List.copyOf(levels);
    }

    /**
     * maxRps 不在阶梯上时（如 50 起每级加 100、上限 400），最后补一级 maxRps，保证压到上限；maxRps 小于 startRps 时只有 startRps 这一级。
     */
    public static StairSchedule of(double startRps, double stepRps, int stepSec, double maxRps) {
        if (startRps <= 0 || stepRps <= 0 || stepSec <= 0) {
            throw new IllegalArgumentException("startRps、stepRps、stepSec 都必须大于 0");
        }
        List<Level> levels = new ArrayList<>();
        double rps = startRps;
        while (true) {
            levels.add(new Level(levels.size() + 1, rps, levels.size() * stepSec, stepSec));
            if (rps >= maxRps - 1e-9) {
                break;
            }
            rps = Math.min(rps + stepRps, maxRps);
        }
        return new StairSchedule(levels);
    }

    /** 整个阶梯的时长（秒）。 */
    public int totalSec() {
        return levels.stream().mapToInt(Level::durationSec).sum();
    }

    /** 整个阶梯一共发出的请求数（向上取整）。 */
    public long totalRequests() {
        return (long) Math.ceil(
                levels.stream().mapToDouble(l -> l.rps() * l.durationSec()).sum());
    }

    /** 最高一级的每秒请求数。 */
    public double peakRps() {
        return levels.getLast().rps();
    }
}
