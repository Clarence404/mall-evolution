package io.github.clarence404.mall.gateway;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关按「用户 + 接口」限流。RedisRateLimiter 的 Redis key 只由 KeyResolver 的结果决定，所以要把路由 ID 拼进去，
 * 否则同一用户在所有路由上共用一个桶。未登录请求取不到 principal，按默认的 deny-empty-key 直接拒绝。
 *
 * <p>Redis 故障时 RedisRateLimiter 默认放行，这是有意的选择：限流挂了不能让下单跟着挂。
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    @Bean
    KeyResolver userRouteKeyResolver() {
        return exchange -> exchange.getPrincipal() // 网关已校验 JWT，principal 名即用户 ID
                .map(p -> p.getName() + ":"
                        + exchange.<Route>getAttribute(GATEWAY_ROUTE_ATTR).getId());
    }
}
