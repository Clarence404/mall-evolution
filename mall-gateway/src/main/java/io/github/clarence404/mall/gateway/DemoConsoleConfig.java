package io.github.clarence404.mall.gateway;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * 仅演示环境（demo profile）：网关在 {@code /} 托管演示控制台（一个静态页面），并多一条路由把
 * {@code /api/demo/inventory/**} 转到库存服务的 {@code /internal/demo/**}（故障注入开关、库存查看、给流量模拟器用的补货）。
 *
 * <p>生产不启用：没有这个页面，也没有这条路由，网关仍然只路由 {@code /api/**} 的业务接口、拒绝 {@code /internal/**}。
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
class DemoConsoleConfig {

    static final Resource CONSOLE = new ClassPathResource("demo/index.html");

    @Bean
    RouterFunction<ServerResponse> demoConsolePage() {
        return RouterFunctions.route(
                GET("/").or(GET("/index.html")),
                req -> ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(CONSOLE));
    }

    /** 走到这条路由的请求仍要带合法 JWT（由主过滤链校验）。 */
    @Bean
    RouteLocator demoInventoryRoute(RouteLocatorBuilder builder) {
        return builder.routes()
                .route(
                        "demo-inventory",
                        r -> r.order(1)
                                .path("/api/demo/inventory/**")
                                .filters(
                                        f -> f.rewritePath("/api/demo/inventory/(?<rest>.*)", "/internal/demo/${rest}"))
                                .uri("lb://inventory-service"))
                .build();
    }

    /** 控制台页面本身匿名可访问；排在主过滤链前面，只匹配这两个路径。 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityWebFilterChain demoConsoleSecurity(ServerHttpSecurity http) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/", "/index.html"))
                .authorizeExchange(a -> a.anyExchange().permitAll())
                .build();
    }
}
