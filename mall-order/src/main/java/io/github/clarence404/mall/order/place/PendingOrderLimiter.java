package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.id.SnowflakeIdGenerator;
import io.github.clarence404.mall.order.api.OrderErrorCode;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 下单频控：网关限流挡得住脚本刷接口，挡不住 300 个账号每人慢慢下几单。放在计价之后、reserve 之前。
 *
 * <p>这里没有加锁，同一用户的并发请求可能都通过检查、多出一单；网关已经把每个用户限制在每秒 1 次，这点误差可以接受。
 */
@Component
public class PendingOrderLimiter {

    static final int MAX_PENDING_PER_SKU = 2;
    private static final Duration LOOK_BACK = Duration.ofMinutes(30);

    private final OrderMapper orderMapper;
    private final Counter rejected;

    public PendingOrderLimiter(OrderMapper orderMapper, MeterRegistry registry) {
        this.orderMapper = orderMapper;
        this.rejected = registry.counter("order.pending.limit"); // 告警：持续 5 分钟高于压测基线
    }

    public void check(long userId, List<PricedItem> items) {
        long minId = SnowflakeIdGenerator.minIdAt(System.currentTimeMillis() - LOOK_BACK.toMillis());
        List<Long> skuIds = items.stream().map(PricedItem::skuId).toList();
        for (OrderMapper.SkuPendingCount row : orderMapper.countPendingBySku(userId, minId, skuIds)) {
            if (row.count() >= MAX_PENDING_PER_SKU) {
                rejected.increment();
                throw new BizException(OrderErrorCode.ORDER_PENDING_LIMIT, "您有未支付的订单，请先完成支付");
            }
        }
    }
}
