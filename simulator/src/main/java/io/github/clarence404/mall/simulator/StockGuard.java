package io.github.clarence404.mall.simulator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存保护：长期运行时，已支付的订单会一直消耗库存（未支付的会在关单后归还），几天后所有商品都会「库存不足」，下单量曲线也就没了。
 *
 * <p>两道保险：
 *
 * <ol>
 *   <li>定时看库存（demo profile 的 {@code GET /api/demo/inventory/stocks}），低于 low-watermark 就调补货接口补到 restock-to
 *   <li>补货接口不可用（比如没开 demo profile、或库存服务还是旧版本没有这个接口）时，低于 floor 就不再买这个商品，只逛不买
 * </ol>
 *
 * <p>这里只做判断、不发请求（发请求在 {@link TrafficSimulator}），方便单元测试。多个虚拟用户线程同时读，状态放在并发容器里。
 */
public final class StockGuard {

    /** 看完一次库存之后要做什么。 */
    public enum Action {
        /** 够用，什么都不做。 */
        NONE,
        /** 低于水位，去补货。 */
        RESTOCK
    }

    private final int lowWatermark;
    private final int floor;
    private final Map<Long, Integer> lastStock = new ConcurrentHashMap<>();
    private volatile boolean restockAvailable = true;

    /**
     * 创建。
     *
     * @param lowWatermark 低于多少补货
     * @param floor 补不了货时，低于多少就不再买
     */
    public StockGuard(int lowWatermark, int floor) {
        this.lowWatermark = lowWatermark;
        this.floor = floor;
    }

    /**
     * 记下最新看到的库存，并决定要不要补货。
     *
     * @param skuId 商品
     * @param stock 可售数量
     * @return 补货接口可用且低于水位时 RESTOCK
     */
    public Action onStock(long skuId, int stock) {
        lastStock.put(skuId, stock);
        return restockAvailable && stock < lowWatermark ? Action.RESTOCK : Action.NONE;
    }

    /** 补货接口不存在（404）或被拒绝：以后不再尝试，改为低于 floor 就停买。 */
    public void markRestockUnavailable() {
        restockAvailable = false;
    }

    /**
     * 补货接口是否可用。
     *
     * @return 没失败过就算可用
     */
    public boolean restockAvailable() {
        return restockAvailable;
    }

    /**
     * 这个商品现在还能不能下单。
     *
     * @param skuId 商品
     * @return 还没看到过库存（接口不可用）时放行，由每小时下单上限兜底；补不了货且低于 floor 时不放行
     */
    public boolean allowOrder(long skuId) {
        Integer stock = lastStock.get(skuId);
        if (stock == null) {
            return true;
        }
        if (stock <= 0) {
            return false; // 卖光了：下单也只会 409，不如不点
        }
        return restockAvailable || stock > floor;
    }

    /**
     * 最近看到的库存（打印统计用）。
     *
     * @return 商品 → 可售数量
     */
    public Map<Long, Integer> snapshot() {
        return Map.copyOf(lastStock);
    }
}
