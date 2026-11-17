package io.github.clarence404.mall.gateway;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 按路由的总量限流（单机），默认一条都不配、不限。例：
 *
 * <pre>
 * mall:
 *   gateway:
 *     route-limits:
 *       anonymous: {permits-per-second: 800, burst: 1600}
 * </pre>
 *
 * <p>和路由上的 RequestRateLimiter（Redis，按「用户 + 路由」）是两回事：那个防单个用户刷接口，这个保护后端的总容量，
 * 匿名路由（商品详情、登录）没有用户身份可按，只能靠它。用 Map 而不是写进 routes 列表，是因为 routes 是列表，
 * 任何一处覆盖都会整体替换（见 deploy/nacos/mall-gateway.yaml 的说明），Map 可以只覆盖一条：
 * {@code --mall.gateway.route-limits.anonymous.permits-per-second=800} 就能单独打开。
 *
 * @param routeLimits 路由 ID → 阈值
 */
@ConfigurationProperties(prefix = "mall.gateway")
record RouteLimitProperties(Map<String, Limit> routeLimits) {

    RouteLimitProperties {
        routeLimits = routeLimits == null ? Map.of() : Map.copyOf(routeLimits);
    }

    /**
     * @param permitsPerSecond 每秒放行多少个请求（本网关实例）
     * @param burst 最多攒多少个令牌，允许的瞬时突发；不配时等于 permitsPerSecond
     */
    record Limit(double permitsPerSecond, Integer burst) {

        int burstOrDefault() {
            return burst != null ? burst : (int) Math.max(1, Math.ceil(permitsPerSecond));
        }
    }
}
