package io.github.clarence404.mall.gateway;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;

/**
 * 网关作为资源服务器统一校验 JWT；下游服务仍用同一把密钥再校验一次，不只凭请求头里的用户 ID 放行。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class GatewaySecurityConfig {

    /**
     * 管理端口的过滤链：只在配置了独立管理端口（management.server.port，与业务端口不同）时才会匹配到请求。
     *
     * <p>Prometheus 只能从管理端口匿名抓取 /actuator/prometheus；业务端口（对外入口）上它仍走 gatewaySecurity，匿名 401。
     * 管理端口上除健康检查、info、prometheus 以外的端点一律拒绝。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityWebFilterChain managementPortSecurity(ServerHttpSecurity http, Environment env) {
        return http.securityMatcher(exchange -> onManagementPort(env, exchange)
                        ? ServerWebExchangeMatcher.MatchResult.match()
                        : ServerWebExchangeMatcher.MatchResult.notMatch())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(a -> a.pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .anyExchange()
                        .denyAll())
                .build();
    }

    /**
     * 请求是否打在独立的管理端口上。Spring Boot 在管理端口启动后把实际端口写进 local.management.port；
     * 没有独立管理端口时它是 local.server.port 的别名，所以要求两者不同，这时这条过滤链永远不匹配。
     */
    static boolean onManagementPort(Environment env, ServerWebExchange exchange) {
        Integer managementPort = env.getProperty("local.management.port", Integer.class);
        Integer serverPort = env.getProperty("local.server.port", Integer.class);
        InetSocketAddress local = exchange.getRequest().getLocalAddress();
        return managementPort != null
                && managementPort > 0
                && !managementPort.equals(serverPort)
                && local != null
                && local.getPort() == managementPort;
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(a -> a.pathMatchers(
                                "/api/auth/**", "/api/payments/notify/**", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/products/**")
                        .permitAll()
                        .pathMatchers("/internal/**")
                        .denyAll() // 双保险：网关本来就不路由 /internal/**
                        .anyExchange()
                        .authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtDecoder(jwtDecoder)))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${mall.security.jwt.secret}") String secret, @Value("${mall.security.jwt.issuer}") String issuer) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(
                        new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer)));
        return decoder;
    }
}
