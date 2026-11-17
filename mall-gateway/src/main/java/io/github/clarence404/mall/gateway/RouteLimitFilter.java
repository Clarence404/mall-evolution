package io.github.clarence404.mall.gateway;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 按路由的总量限流（单机令牌桶），阈值见 {@link RouteLimitProperties}。没配任何路由时直接放行，行为与不加这个过滤器一样。
 *
 * <p>超过阈值返回 429 和 {@code Retry-After: 1}，并带 {@code X-RateLimit-Scope: route}，和按用户限流的 429 区分开。
 * 被拒绝的请求在网关就结束，不占后端的线程和连接——突发流量超过容量时，宁可拒掉一部分，也不让所有人一起变慢。
 * 指标：{@code gateway_route_limited_total{route}}。
 */
@Slf4j
@Component
@EnableConfigurationProperties(RouteLimitProperties.class)
class RouteLimitFilter implements GlobalFilter, Ordered {

    static final String SCOPE_HEADER = "X-RateLimit-Scope";

    private final Map<String, TokenBucket> buckets;
    private final Map<String, Counter> rejected;

    @Autowired
    RouteLimitFilter(RouteLimitProperties props, MeterRegistry registry) {
        this(props, registry, System::nanoTime);
    }

    /** 测试用：传入可拨动的纳秒时钟。 */
    RouteLimitFilter(RouteLimitProperties props, MeterRegistry registry, LongSupplier nanoClock) {
        this.buckets = props.routeLimits().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> new TokenBucket(
                                e.getValue().permitsPerSecond(), e.getValue().burstOrDefault(), nanoClock)));
        this.rejected = props.routeLimits().keySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        id -> id,
                        id -> Counter.builder("gateway.route.limited")
                                .description("被按路由总量限流拒绝的请求数")
                                .tag("route", id)
                                .register(registry)));
        if (!buckets.isEmpty()) {
            log.info("按路由总量限流（单机）已开启：{}", props.routeLimits());
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (buckets.isEmpty()) {
            return chain.filter(exchange);
        }
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        TokenBucket bucket = route == null ? null : buckets.get(route.getId());
        if (bucket == null || bucket.tryAcquire()) {
            return chain.filter(exchange);
        }
        rejected.get(route.getId()).increment();
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        exchange.getResponse().getHeaders().set(SCOPE_HEADER, "route");
        return exchange.getResponse().setComplete();
    }

    /** 排在路由过滤器（含按用户的 RequestRateLimiter）之前：总量超了就不必再去 Redis 查用户的桶。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
