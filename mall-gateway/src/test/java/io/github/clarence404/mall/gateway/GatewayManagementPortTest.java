package io.github.clarence404.mall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 网关的指标只从管理端口匿名抓取：开了独立管理端口（这里用随机端口）后，
 * 对外入口上匿名访问 /actuator/prometheus 仍是 401，管理端口上能拿到指标，管理端口上其他端点拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("it")
class GatewayManagementPortTest {

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    @Test
    void prometheusIsNotPublicOnServerPort() {
        assertThat(managementPort).isNotEqualTo(serverPort);
        client(serverPort)
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void prometheusIsScrapableOnManagementPort() {
        String body = client(managementPort)
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).contains("jvm_memory_used_bytes", "application=\"mall-gateway\"");
    }

    @Test
    void otherActuatorEndpointsAreDeniedOnManagementPort() {
        client(managementPort)
                .get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus()
                .isOk();
        client(managementPort)
                .get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus()
                .value(s -> assertThat(s).isIn(401, 403));
    }

    private static WebTestClient client(int port) {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}
