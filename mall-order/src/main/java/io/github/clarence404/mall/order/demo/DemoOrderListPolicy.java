package io.github.clarence404.mall.order.demo;

import io.github.clarence404.mall.order.query.OrderListPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 仅演示环境：{@code mall.demo.order-list.index-hint=false} 时订单列表的 SQL 不带索引提示，由 MySQL 自己选索引
 * （它会选 uk_user_biz 再排序）。默认 true，和正式环境一样。高性能实验 I 用它对比修复前后。
 *
 * <p>{@code mall.demo.order-list.plain-cursor=true} 时游标直接用 id 的十进制字符串，默认 false。微基准里这种写法比
 * JSON + Base64 快很多，实验 I 用它看这点差别在接口上量不量得出来。
 */
@Slf4j
@Component
@Profile("demo")
class DemoOrderListPolicy implements OrderListPolicy {

    private final boolean indexHint;
    private final boolean plainCursor;

    DemoOrderListPolicy(
            @Value("${mall.demo.order-list.index-hint:true}") boolean indexHint,
            @Value("${mall.demo.order-list.plain-cursor:false}") boolean plainCursor) {
        this.indexHint = indexHint;
        this.plainCursor = plainCursor;
        if (!indexHint || plainCursor) {
            log.warn("订单列表按演示开关运行：索引提示={}，游标用十进制字符串={}", indexHint, plainCursor);
        }
    }

    @Override
    public boolean plainCursor() {
        return plainCursor;
    }

    @Override
    public boolean indexHint() {
        return indexHint;
    }
}
