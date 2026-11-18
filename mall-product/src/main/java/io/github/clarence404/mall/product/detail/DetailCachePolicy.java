package io.github.clarence404.mall.product.detail;

import java.util.function.Supplier;

/**
 * 商品详情两级缓存的开关，正式环境没有实现（全部打开、回源原样执行，即 {@link #DEFAULT}）。
 *
 * <p>只有演示环境（demo profile）注册了实现：高并发实验 F 靠它逐层关掉缓存，对比「没有缓存 / 只有 Redis / 两级缓存」，
 * 以及关掉单飞、关掉空值缓存之后的击穿与穿透。见 experiments/f-cache-layers。
 */
public interface DetailCachePolicy {

    DetailCachePolicy DEFAULT = new DetailCachePolicy() {};

    /** 用不用本机 Caffeine（L1）。关掉后每个请求都直接走 Redis / 数据库。 */
    default boolean l1Enabled() {
        return true;
    }

    /** 用不用 Redis（L2）。关掉后既不读也不写 Redis，L1 未命中直接查库。 */
    default boolean l2Enabled() {
        return true;
    }

    /** L1 未命中时是否单飞（同一个 SKU 本 JVM 只有一个线程去加载）。关掉后并发未命中的请求各自加载、各自回填。 */
    default boolean singleFlightEnabled() {
        return true;
    }

    /** 不存在的 SKU 是否缓存空值（Redis 60 秒 + L1）。关掉后两层都不缓存，每次请求都查库。 */
    default boolean nullCacheEnabled() {
        return true;
    }

    /** 包住一次回源查库；正式环境直接执行，不加任何东西。 */
    default <T> T aroundDbLoad(Supplier<T> load) {
        return load.get();
    }
}
