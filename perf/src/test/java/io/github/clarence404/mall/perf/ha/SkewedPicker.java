package io.github.clarence404.mall.perf.ha;

import java.util.List;

/**
 * 按热度偏斜挑一个：hotShare 比例的请求给第一个（最热的），其余平均分给剩下的。
 *
 * <p>多级缓存实验用它在几个 SKU 之间分配浏览量：演示数据里上架的商品只有 3 个，谈不上长尾分布，用「一个热点 + 其余均分」
 * 这种最简单的偏斜就够了。只有一个候选时全部给它。
 */
public final class SkewedPicker {

    private final List<String> items;
    private final double hotShare;

    public SkewedPicker(List<String> items, double hotShare) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("至少要有一个候选");
        }
        if (hotShare < 0 || hotShare > 1) {
            throw new IllegalArgumentException("hotShare 是比例，应在 0–1 之间，实际：" + hotShare);
        }
        this.items = List.copyOf(items);
        this.hotShare = hotShare;
    }

    /** 最热的那个（第一个）。 */
    public String hottest() {
        return items.getFirst();
    }

    /**
     * 挑一个。
     *
     * @param u [0, 1) 上的均匀随机数：小于 hotShare 给最热的，其余按比例落到剩下的候选上
     */
    public String pick(double u) {
        if (items.size() == 1 || u < hotShare) {
            return items.getFirst();
        }
        double rest = (u - hotShare) / (1 - hotShare); // 仍在 [0, 1) 上均匀
        int i = 1 + (int) (rest * (items.size() - 1));
        return items.get(Math.min(i, items.size() - 1));
    }
}
