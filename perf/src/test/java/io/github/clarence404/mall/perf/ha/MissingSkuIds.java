package io.github.clarence404.mall.perf.ha;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

/**
 * 不存在的 SKU ID（缓存穿透实验用）。都是正整数——商品详情接口先做格式校验，不是正整数的请求到不了缓存那一层。
 *
 * <p>两种取法：
 *
 * <ul>
 *   <li>{@link #repeat}：固定的一小批 ID 来回请求（爬虫、写错的链接）。空值缓存对它有效：每个 ID 只回源一次
 *   <li>{@link #random}：每个请求一个新的随机 ID（恶意构造）。空值缓存对它无效，每次都未命中、都回源，还白占缓存
 * </ul>
 *
 * <p>默认从 9 万亿起：演示数据的 SKU 是 2 万多，雪花 ID 是 18–19 位，都碰不到这一段。
 */
public final class MissingSkuIds {

    /** 默认的起点。 */
    public static final long DEFAULT_BASE = 9_000_000_000_000L;

    /** 随机取法的范围：起点之后的一万亿个 ID 里取，重复的概率可以忽略。 */
    static final long RANDOM_SPAN = 1_000_000_000_000L;

    private MissingSkuIds() {}

    /** base、base + 1、…、base + count − 1 循环取。 */
    public static LongSupplier repeat(long base, int count) {
        check(base);
        if (count < 1) {
            throw new IllegalArgumentException("count 至少为 1，实际：" + count);
        }
        AtomicLong next = new AtomicLong();
        return () -> base + next.getAndIncrement() % count;
    }

    /** 每次在 [base + skip, base + skip + 一万亿) 里随机取一个；skip 用来避开 {@link #repeat} 用掉的那一小段。 */
    public static LongSupplier random(long base, int skip) {
        return random(base, skip, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    /** 测试用：传入随机源（给定上界，返回 [0, 上界) 里的数）。 */
    static LongSupplier random(long base, int skip, LongUnaryOperator nextLong) {
        check(base);
        return () -> base + skip + nextLong.applyAsLong(RANDOM_SPAN);
    }

    private static void check(long base) {
        if (base <= 0) {
            throw new IllegalArgumentException("起点必须是正整数（接口校验 skuId 为正），实际：" + base);
        }
    }
}
