package io.github.clarence404.mall.perf.ha;

import java.util.ArrayList;
import java.util.List;

/**
 * 「平时 → 高峰 → 平时」的日程：从 0 秒起每秒 baseRps，第 burstAtSec 秒起升到 burstRps、持续 burstSec 秒，再回到 baseRps，
 * 直到第 durationSec 秒。升降都是一步到位，不设过渡。
 *
 * <p>单独拿出来是为了三件事用同一份算法：Gatling 的注入步骤、events.csv 里高峰开始与结束的时刻、以及这一轮一共会发多少请求
 * （要多少个不重复的实验用户、要备多少库存）。
 *
 * @param baseRps 平时每秒请求数
 * @param burstRps 高峰每秒请求数
 * @param burstAtSec 高峰从第几秒开始
 * @param burstSec 高峰持续多少秒
 * @param durationSec 总时长（秒）
 */
public record BurstSchedule(double baseRps, double burstRps, int burstAtSec, int burstSec, int durationSec) {

    /**
     * 一段恒定速率。
     *
     * @param rps 每秒请求数
     * @param startSec 从第几秒起
     * @param durationSec 持续多少秒
     * @param burst 是不是高峰那一段
     */
    public record Phase(double rps, int startSec, int durationSec, boolean burst) {}

    public BurstSchedule {
        if (baseRps <= 0 || burstRps <= 0) {
            throw new IllegalArgumentException("baseRps、burstRps 都必须大于 0");
        }
        if (burstAtSec < 0 || burstSec < 0 || durationSec <= 0) {
            throw new IllegalArgumentException("burstAtSec、burstSec 不能为负，durationSec 必须大于 0");
        }
        if (burstAtSec + burstSec > durationSec) {
            throw new IllegalArgumentException(
                    "高峰（第 " + burstAtSec + " 秒起 " + burstSec + " 秒）超出了总时长 " + durationSec + " 秒");
        }
    }

    /** 高峰结束的时刻（秒）。 */
    public int burstEndSec() {
        return burstAtSec + burstSec;
    }

    /** 按时间先后的各段；长度为 0 的段不出现（高峰从第 0 秒开始、或一直持续到结束时）。 */
    public List<Phase> phases() {
        List<Phase> phases = new ArrayList<>();
        if (burstAtSec > 0) {
            phases.add(new Phase(baseRps, 0, burstAtSec, false));
        }
        if (burstSec > 0) {
            phases.add(new Phase(burstRps, burstAtSec, burstSec, true));
        }
        if (durationSec > burstEndSec()) {
            phases.add(new Phase(baseRps, burstEndSec(), durationSec - burstEndSec(), false));
        }
        return List.copyOf(phases);
    }

    /** 一共发出的请求数（向上取整）。 */
    public long totalRequests() {
        return (long) Math.ceil(
                phases().stream().mapToDouble(p -> p.rps() * p.durationSec()).sum());
    }
}
