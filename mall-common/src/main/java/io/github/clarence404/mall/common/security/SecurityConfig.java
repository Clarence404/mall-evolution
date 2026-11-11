package io.github.clarence404.mall.common.security;

import io.github.clarence404.mall.common.error.CommonErrorCode;
import io.github.clarence404.mall.common.error.GlobalExceptionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 无状态鉴权：访问令牌放在 {@code Authorization: Bearer}，两台主机都能独立校验，不需要共享会话。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /**
     * 管理端口的过滤链：只在配置了独立管理端口（management.server.port，与业务端口不同）时才会匹配到请求。
     *
     * <p>Prometheus 只能从管理端口匿名抓取 /actuator/prometheus；管理端口不对外暴露（不进网关、不进 Service），
     * 只给同网段的 Prometheus 访问。业务端口上 /actuator/prometheus 仍走下面的 apiSecurity，匿名访问一律 401。
     * 管理端口上除健康检查、info、prometheus 以外的端点（如 /actuator/metrics）一律拒绝。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain managementPortSecurity(HttpSecurity http, Environment env) throws Exception {
        http.securityMatcher(request -> onManagementPort(env, request.getLocalPort()))
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a -> a.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                                .permitAll()
                                .anyRequest()
                                .denyAll());
        return http.build();
    }

    /**
     * 请求是否打在独立的管理端口上。
     *
     * <p>Spring Boot 在管理端口的 Web 服务器启动后把实际端口写进 local.management.port（随机端口 0 也是实际值）。
     * 注意：没有独立管理端口时，local.management.port 是 local.server.port 的别名，所以要求两者不同；
     * 这时这条过滤链永远不匹配，行为与改动前完全一样。
     */
    static boolean onManagementPort(Environment env, int localPort) {
        Integer managementPort = env.getProperty("local.management.port", Integer.class);
        Integer serverPort = env.getProperty("local.server.port", Integer.class);
        return managementPort != null
                && managementPort > 0
                && !managementPort.equals(serverPort)
                && managementPort == localPort;
    }

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(
                                "/api/auth/**",
                                "/api/payments/notify/**", // 渠道回调：免 JWT，改验渠道签名
                                "/internal/**", // 只在内网可达，网关不路由
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/**")
                        .permitAll()
                        .requestMatchers("/api/admin/**") // 运营后台：令牌里要有 ADMIN 角色
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(o -> o.jwt(j ->
                                j.decoder(jwtDecoder).jwtAuthenticationConverter(LoginUserAuthentication.converter()))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(
                        e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(props.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(props.issuer())));
        return decoder;
    }

    /** 已登录但角色不够（如普通用户调后台接口）时返回 403 的 ProblemDetail。 */
    @Bean
    AccessDeniedHandler problemAccessDeniedHandler(JsonMapper jsonMapper) {
        return (request, response, ex) -> {
            ProblemDetail pd = GlobalExceptionHandler.problem(CommonErrorCode.FORBIDDEN, "需要运营后台权限");
            pd.setInstance(java.net.URI.create(request.getRequestURI()));
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            jsonMapper.writeValue(response.getOutputStream(), pd);
        };
    }

    /** 未登录或令牌无效时返回 401 的 ProblemDetail，与业务错误格式一致。 */
    @Bean
    AuthenticationEntryPoint problemAuthenticationEntryPoint(JsonMapper jsonMapper) {
        return (request, response, ex) -> {
            ProblemDetail pd = GlobalExceptionHandler.problem(CommonErrorCode.UNAUTHORIZED, "未登录或登录已过期");
            pd.setInstance(java.net.URI.create(request.getRequestURI()));
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            jsonMapper.writeValue(response.getOutputStream(), pd);
        };
    }
}
