package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.clarence404.mall.payment.api.PaymentApi;
import io.github.clarence404.mall.support.TestTokens;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 下单经真实 HTTP 调库存服务（JDK 自带的 HttpServer 做替身，经 LoadBalancer 按服务名找到它），不替换 InventoryApi：
 * 验证 4xx 原样透传、读超时计入 inventory-reserve 熔断器。其他 H2 测试把 InventoryApi 换成了 Mockito 替身，
 * 覆盖不到 HTTP 客户端上的装配（比如 Spring Cloud 给 HTTP 接口客户端自动加的那层熔断）。
 *
 * <p>熔断阈值在这里按次数缩小（3 次起算），生产配置的 10 秒 100 次见 StabilityH2Test。
 */
@SpringBootTest(
        properties = {
            "resilience4j.circuitbreaker.instances.inventory-reserve.sliding-window-type=COUNT_BASED",
            "resilience4j.circuitbreaker.instances.inventory-reserve.sliding-window-size=10",
            "resilience4j.circuitbreaker.instances.inventory-reserve.minimum-number-of-calls=3",
            "resilience4j.circuitbreaker.instances.inventory-reserve.wait-duration-in-open-state=1m"
        })
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2"})
class InventoryHttpH2Test {

    private static final String BODY = """
            {"lines":[{"skuId":"20003","quantity":1}],
             "address":{"receiver":"张三","phone":"13800000000","detail":"杭州市西湖区"}}
            """;

    /** 替身的行为：reserve 返回什么。release 一律 200。 */
    enum Mode {
        OK,
        STOCK_INSUFFICIENT,
        SLOW
    }

    static volatile Mode mode = Mode.OK;
    static final AtomicInteger reserveCalls = new AtomicInteger();
    static final AtomicInteger releaseCalls = new AtomicInteger();
    static final HttpServer INVENTORY = startInventoryStub();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    PaymentApi paymentApi;

    @DynamicPropertySource
    static void inventoryInstance(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.cloud.discovery.client.simple.instances.inventory-service[0].uri",
                () -> "http://localhost:" + INVENTORY.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        INVENTORY.stop(0);
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.update("UPDATE t_order SET status = 50 WHERE status = 10"); // 下单频控：清掉遗留的待支付订单
        circuitBreakerRegistry.circuitBreaker("inventory-reserve").reset();
        reserveCalls.set(0);
        releaseCalls.set(0);
        mode = Mode.OK;
    }

    @Test
    void stockInsufficientFromInventoryIsPassedThroughAs409() throws Exception {
        mode = Mode.STOCK_INSUFFICIENT;
        placeOrder()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
        assertThat(releaseCalls).hasValue(0); // 明确失败，什么都没扣，不用释放
        assertThat(circuitBreakerRegistry
                        .circuitBreaker("inventory-reserve")
                        .getMetrics()
                        .getNumberOfFailedCalls())
                .isZero(); // 4xx 是业务结果，不计入熔断
    }

    @Test
    void reserveSlowerThanReadTimeoutOpensCircuitAndReturns503() throws Exception {
        mode = Mode.SLOW;
        // 每单：reserve 读超时（失败）→ 立即 release（成功）。3 次起算、失败率 ≥ 50%：第 2 单后为 2 败 1 成，熔断打开
        placeOrder()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));
        placeOrder()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("inventory-reserve");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);

        int reservesBefore = reserveCalls.get();
        placeOrder()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                .andExpect(jsonPath("$.code").value("SYSTEM_BUSY"));
        assertThat(reserveCalls).hasValue(reservesBefore); // 熔断打开后请求没发出去
    }

    private ResultActions placeOrder() throws Exception {
        return mvc.perform(post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }

    private static HttpServer startInventoryStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/internal/inventory/reserve", InventoryHttpH2Test::reserve);
            server.createContext("/internal/inventory/release", ex -> {
                releaseCalls.incrementAndGet();
                respond(ex, 200, MediaType.APPLICATION_JSON_VALUE, "");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reserve(HttpExchange ex) throws IOException {
        reserveCalls.incrementAndGet();
        switch (mode) {
            case STOCK_INSUFFICIENT ->
                respond(
                        ex,
                        409,
                        MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        "{\"type\":\"https://api.mall.example/problems/stock-insufficient\",\"title\":\"库存不足\","
                                + "\"status\":409,\"detail\":\"SKU 20003 库存不足\",\"code\":\"STOCK_INSUFFICIENT\"}");
            case SLOW -> {
                try {
                    Thread.sleep(800); // 订单侧读超时 500ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(ex, 200, MediaType.APPLICATION_JSON_VALUE, "\"OK\"");
            }
            default -> respond(ex, 200, MediaType.APPLICATION_JSON_VALUE, "\"OK\"");
        }
    }

    private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
        ex.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, contentType);
        try {
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
        } catch (IOException ignored) {
            // 客户端已经读超时断开
        } finally {
            ex.close();
        }
    }
}
