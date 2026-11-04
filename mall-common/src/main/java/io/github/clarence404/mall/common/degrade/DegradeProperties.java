package io.github.clarence404.mall.common.degrade;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 降级开关：统一放在 Nacos（mall-app.yaml），推送后由 Spring Cloud 重新绑定，实时生效。
 *
 * <p>下单、支付、库存三条核心链路不设降级开关，只靠限流和熔断保护。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mall.degrade")
public class DegradeProperties {

    /** 商品详情、购物车的「猜你喜欢」；关闭后返回运营配置的固定列表。 */
    private boolean recommendEnabled = true;

    /** 订单详情、我的页面实时查积分；关闭后不再查。 */
    private boolean pointsDisplayEnabled = true;

    /** 订单列表只查近 N 个月；0 表示不限制。 */
    private int orderListMonths = 0;
}
