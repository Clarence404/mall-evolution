package io.github.clarence404.mall.order;

import io.github.clarence404.mall.order.query.OrderView;
import io.github.clarence404.mall.support.IntegrationTestBase;
import io.github.clarence404.mall.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/** 接口层约定（状态码、响应头、错误码）每条至少有一个测试守住。需要 Docker。 */
@AutoConfigureRestTestClient
class PlaceOrderApiTests extends IntegrationTestBase {

    private static final String BODY = """
            {"lines":[{"skuId":"20001","quantity":2}],
             "address":{"receiver":"张三","phone":"13800000000","detail":"杭州市西湖区"}}
            """;

    @Autowired
    RestTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sameKeyReplaysFirstOrder() {
        String key = UUID.randomUUID().toString();

        String first = placeOrder(key, BODY)
                .expectStatus()
                .isCreated()
                .expectBody(OrderView.class)
                .returnResult()
                .getResponseBody()
                .orderNo();

        placeOrder(key, BODY)
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals("Idempotent-Replayed", "true")
                .expectBody()
                .jsonPath("$.orderNo")
                .isEqualTo(first);
    }

    @Test
    void cancelShippedOrderReturns409() {
        String orderNo = createOrder(10001L);
        jdbc.update("UPDATE t_order SET status = 30 WHERE order_no = ?", orderNo);

        client.post()
                .uri("/api/orders/{orderNo}/cancel", orderNo)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ORDER_STATUS_CONFLICT");
    }

    @Test
    void missingIdempotencyKeyReturns400() {
        client.post()
                .uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                .contentType(MediaType.APPLICATION_JSON)
                .body(BODY)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void otherUsersOrderReturns404() {
        String orderNo = createOrder(10001L);

        client.get()
                .uri("/api/orders/{orderNo}", orderNo)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10002L))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void cancelPendingOrderPublishesOrderCancelled() {
        org.mockito.Mockito.when(paymentApi.close(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(io.github.clarence404.mall.payment.api.ClosePaymentResult.CLOSED);
        String orderNo = createOrder(10001L);

        client.post()
                .uri("/api/orders/{orderNo}/cancel", orderNo)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("CANCELLED");

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE topic = 'order-cancelled' AND msg_key = ?",
                        Integer.class,
                        orderNo))
                .isEqualTo(1);
    }

    private String createOrder(long userId) {
        return client.post()
                .uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(userId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BODY)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(OrderView.class)
                .returnResult()
                .getResponseBody()
                .orderNo();
    }

    private RestTestClient.ResponseSpec placeOrder(String key, String body) {
        return client.post()
                .uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }
}
