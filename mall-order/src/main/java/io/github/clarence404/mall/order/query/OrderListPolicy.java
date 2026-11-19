package io.github.clarence404.mall.order.query;

/**
 * 订单列表查询的开关，正式环境没有实现（即 {@link #DEFAULT}：加索引提示）。
 *
 * <p>只有演示环境（demo profile）注册了实现：高性能实验 I 靠它把索引提示关掉，复现「优化器自己选索引」时订单列表的耗时，
 * 再和加了提示的对照；另一个开关把游标换成最简单的写法，用来验证「微基准里快了几十倍的改动，放回接口里看不看得出来」。
 * 见 experiments/i-locate 与 {@code OrderMapper.listByUser} 的说明。
 */
public interface OrderListPolicy {

    OrderListPolicy DEFAULT = new OrderListPolicy() {};

    /** 订单列表的 SQL 带不带 {@code INDEX(t_order idx_user_id)} 提示。 */
    default boolean indexHint() {
        return true;
    }

    /** 游标是不是直接用 id 的十进制字符串（而不是 {@link OrderCursor} 的 JSON + Base64）。正式环境不用：游标对客户端应当不透明。 */
    default boolean plainCursor() {
        return false;
    }
}
