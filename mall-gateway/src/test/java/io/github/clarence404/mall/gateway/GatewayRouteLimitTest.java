package io.github.clarence404.mall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 按路由的总量限流：只配了 anonymous 一条（突发 2 个，之后 100 秒才补 1 个，测试期间不会补上），第 3 个匿名请求在网关直接 429；没配的路由不受影响。
 * 测试里没有下游实例，放行的请求由 LoadBalancer 返回 503——只要不是 429 就说明限流放行了。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "mall.gateway.route-limits.anonymous.permits-per-second=0.01",
            "mall.gateway.route-limits.anonymous.burst=2"
        })
@ActiveProfiles("it")
class GatewayRouteLimitTest {

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meterRegistry;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client =
                WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousRouteIsCappedAndOtherRoutesAreNot() {
        assertThat(get("/api/products/20001").getStatus().value()).isNotEqualTo(429);
        assertThat(get("/api/products/20002").getStatus().value()).isNotEqualTo(429);

        EntityExchangeResult<byte[]> limited = get("/api/products/20003");
        assertThat(limited.getStatus().value()).isEqualTo(429);
        assertThat(limited.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("1");
        assertThat(limited.getResponseHeaders().getFirst(RouteLimitFilter.SCOPE_HEADER))
                .isEqualTo("route");

        // 渠道回调走 payment-notify 路由，没配总量限流
        int notify = client.post()
                .uri("/api/payments/notify/mock")
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value();
        assertThat(notify).isNotEqualTo(429);

        assertThat(meterRegistry
                        .get("gateway.route.limited")
                        .tag("route", "anonymous")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    private EntityExchangeResult<byte[]> get(String uri) {
        return client.get().uri(uri).exchange().expectBody().returnResult();
    }
}
