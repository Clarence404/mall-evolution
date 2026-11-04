package io.github.clarence404.mall.common.security;

import io.github.clarence404.mall.common.error.CommonErrorCode;
import io.github.clarence404.mall.common.error.GlobalExceptionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
