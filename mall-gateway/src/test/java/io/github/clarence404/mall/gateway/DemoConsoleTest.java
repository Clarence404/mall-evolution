package io.github.clarence404.mall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** demo profile：根路径匿名返回演示控制台；演示路由仍要令牌；/internal/** 照样拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"it", "demo"})
class DemoConsoleTest {

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client =
                WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void consolePageIsServedAnonymously() {
        String html = client.get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(html).contains("演示控制台");
    }

    @Test
    void demoRouteStillRequiresToken() {
        client.get().uri("/api/demo/inventory/fault").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void internalPathsStayForbidden() {
        client.get().uri("/internal/demo/fault").exchange().expectStatus().isUnauthorized();
    }
}
