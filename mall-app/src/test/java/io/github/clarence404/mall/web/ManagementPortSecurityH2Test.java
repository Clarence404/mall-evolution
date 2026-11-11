package io.github.clarence404.mall.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.payment.api.PaymentApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 指标只从管理端口匿名抓取：开了独立管理端口（这里用随机端口）后，
 * 业务端口上匿名访问 /actuator/prometheus 仍是 401，管理端口上能拿到 Prometheus 文本格式的指标，
 * 管理端口上其他端点（/actuator/metrics）拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles({"it", "h2"})
class ManagementPortSecurityH2Test {

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    @MockitoBean
    InventoryApi inventoryApi;

    @MockitoBean
    PaymentApi paymentApi;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void prometheusIsNotPublicOnServerPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(serverPort);
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(401);
    }

    @Test
    void prometheusIsScrapableOnManagementPort() throws Exception {
        HttpResponse<String> res = get(managementPort, "/actuator/prometheus");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("jvm_memory_used_bytes", "application=\"mall-app\"");
    }

    @Test
    void otherActuatorEndpointsAreDeniedOnManagementPort() throws Exception {
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode())
                .isEqualTo(200);
        assertThat(get(managementPort, "/actuator/metrics").statusCode()).isIn(401, 403);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
