package io.github.clarence404.mall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 网关统一校验 JWT：匿名接口放行（下游没有实例时网关返回 503），其余接口没有令牌返回 401，/internal/** 一律拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class GatewaySecurityTest {

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client =
                WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void protectedRouteRequiresToken() {
        client.get().uri("/api/orders").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anonymousRoutesPassSecurityAndReachLoadBalancer() {
        int status = client.post()
                .uri("/api/auth/login")
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value();
        assertThat(status).isNotIn(401, 403); // 测试里没有 mall-app 实例，LoadBalancer 返回 503
    }

    @Test
    void validTokenPassesSecurity() {
        int status = client.get()
                .uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void internalPathsAreNeverExposed() {
        client.post()
                .uri("/internal/inventory/deduct")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    private static String bearer() {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "test-only-jwt-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("mall-evolution")
                .subject("10001")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
        return "Bearer "
                + encoder.encode(JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
    }
}
