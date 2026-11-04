package io.github.clarence404.mall.order.place;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大促活动配置（Nacos）：订单里只要有一个活动 SKU，order-timeout 定时消息就按 5 分钟投递，缩短恶意下单锁库存的时间。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mall.promo")
public class PromoProperties {

    private Set<Long> activitySkuIds = new HashSet<>();
    private Duration activityPayTimeout = Duration.ofMinutes(5);
    private Duration normalPayTimeout = Duration.ofMinutes(15);

    public Duration payTimeoutFor(Collection<Long> skuIds) {
        return skuIds.stream().anyMatch(activitySkuIds::contains) ? activityPayTimeout : normalPayTimeout;
    }
}
