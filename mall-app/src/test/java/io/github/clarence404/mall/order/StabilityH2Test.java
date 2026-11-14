package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.common.degrade.DegradeProperties;
import io.github.clarence404.mall.common.id.SnowflakeIdGenerator;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.api.ReserveStockRequest;
import io.github.clarence404.mall.order.place.InventoryReserveClient;
import io.github.clarence404.mall.support.H2TestBase;
import io.github.clarence404.mall.support.TestTokens;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

/** 阶段四的保护在整个上下文里是否生效：熔断与舱壁配置、熔断打开时返回 503 + Retry-After、下单频控、订单列表降级。 */
class StabilityH2Test extends H2TestBase {

    private static final String BODY = """
            {"lines":[{"skuId":"20003","quantity":1}],
             "address":{"receiver":"张三","phone":"13800000000","detail":"杭州市西湖区"}}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    BulkheadRegistry bulkheadRegistry;

    @Autowired
    InventoryReserveClient inventoryReserveClient;

    @Autowired
    DegradeProperties degrade;

    @AfterEach
    void resetProtections() {
        circuitBreakerRegistry.circuitBreaker("inventory-reserve").reset();
        circuitBreakerRegistry.circuitBreaker("inventory-stock-query").reset();
        degrade.setOrderListMonths(0);
    }

    @Test
    void resilience4jInstancesAreBoundFromConfiguration() {
        CircuitBreakerConfig cb =
                circuitBreakerRegistry.circuitBreaker("inventory-reserve").getCircuitBreakerConfig();
        assertThat(cb.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
        assertThat(cb.getSlidingWindowSize()).isEqualTo(10);
        assertThat(cb.getMinimumNumberOfCalls()).isEqualTo(100);
        assertThat(cb.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(cb.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(300));
        // record-failure-predicate：4xx 业务结果不算失败
        ProblemDetail stock = ProblemDetail.forStatus(409);
        stock.setProperty("code", "STOCK_INSUFFICIENT");
        assertThat(cb.getRecordExceptionPredicate()
                        .test(new io.github.clarence404.mall.common.remote.RemoteBizException(
                                org.springframework.http.HttpStatus.CONFLICT, stock)))
                .isFalse();
        assertThat(cb.getRecordExceptionPredicate().test(new ResourceAccessException("timeout")))
                .isTrue();

        assertThat(bulkheadRegistry
                        .bulkhead("inventory-reserve")
                        .getBulkheadConfig()
                        .getMaxConcurrentCalls())
                .isEqualTo(60);
    }

    @Test
    void paymentQueryBulkheadIsConfiguredExplicitly() {
        assertThat(bulkheadRegistry
                        .bulkhead("payment-query")
                        .getBulkheadConfig()
                        .getMaxConcurrentCalls())
                .isEqualTo(20);
    }

    @Test
    void openCircuitFailsFastWith503AndRetryAfterWithoutReleasing() throws Exception {
        when(inventoryApi.reserve(any())).thenThrow(new ResourceAccessException("Read timed out"));
        ReserveStockRequest req = new ReserveStockRequest("X", List.of(new ReserveLine(20003L, 1)));
        for (int i = 0; i < 100; i++) { // minimum-number-of-calls: 100，失败率 100% > 50%
            try {
                inventoryReserveClient.reserve(req);
            } catch (ResourceAccessException expected) {
                // 记为失败
            }
        }
        assertThat(circuitBreakerRegistry.circuitBreaker("inventory-reserve").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        org.mockito.Mockito.clearInvocations(inventoryApi);

        placeOrder(10001L)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
                .andExpect(jsonPath("$.code").value("SYSTEM_BUSY"));
        verify(inventoryApi, never()).reserve(any()); // 熔断打开的 5 秒里，请求在 mall-app 内就失败返回
        verify(inventoryApi, never()).release(any());
    }

    /** 详情页查库存展示也有自己的熔断与舱壁（inventory-stock-query），不和下单的 inventory-reserve 共用。 */
    @Test
    void stockQueryCircuitBreakerAndBulkheadAreConfigured() {
        CircuitBreakerConfig cb =
                circuitBreakerRegistry.circuitBreaker("inventory-stock-query").getCircuitBreakerConfig();
        assertThat(cb.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
        assertThat(cb.getMinimumNumberOfCalls()).isEqualTo(20);
        assertThat(cb.getRecordExceptionPredicate().test(new ResourceAccessException("timeout")))
                .isTrue();
        assertThat(bulkheadRegistry
                        .bulkhead("inventory-stock-query")
                        .getBulkheadConfig()
                        .getMaxConcurrentCalls())
                .isEqualTo(20);
    }

    /** 库存服务持续出错、熔断打开后：详情页不再去调库存服务，照样返回 200，「有货」按兜底值展示。 */
    @Test
    void openStockQueryCircuitStopsCallingInventoryAndDetailStillWorks() throws Exception {
        circuitBreakerRegistry.circuitBreaker("inventory-stock-query").transitionToOpenState();

        mvc.perform(get("/api/products/20002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.skuId").value("20002"))
                .andExpect(jsonPath("$.inStock").value(true));

        verify(inventoryApi, never()).listStocks(any());
    }

    @Test
    void thirdPendingOrderOfSameSkuIsRejected() throws Exception {
        when(inventoryApi.reserve(any())).thenReturn(ReserveResult.OK);

        placeOrder(10002L).andExpect(status().isCreated());
        placeOrder(10002L).andExpect(status().isCreated());
        placeOrder(10002L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_PENDING_LIMIT"));
    }

    @Test
    void orderListOnlyShowsRecentMonthsWhenDegraded() throws Exception {
        long oldId = SnowflakeIdGenerator.minIdAt(
                ZonedDateTime.now().minusMonths(4).toInstant().toEpochMilli());
        jdbcTemplate.update(
                "INSERT INTO t_order (id, order_no, user_id, biz_no, status, total_amount, pay_amount, receiver_name,"
                        + " receiver_phone, receiver_addr) VALUES (?, ?, 10002, ?, 40, 1, 1, 'x', '13800000000', 'x')",
                oldId,
                "OLD" + oldId,
                "old-" + oldId);

        mvc.perform(get("/api/orders").param("size", "50").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10002L)))
                .andExpect(
                        jsonPath("$.items[?(@.orderNo == 'OLD" + oldId + "')]").exists());

        degrade.setOrderListMonths(3); // Nacos 推送后实时生效
        mvc.perform(get("/api/orders").param("size", "50").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10002L)))
                .andExpect(
                        jsonPath("$.items[?(@.orderNo == 'OLD" + oldId + "')]").doesNotExist());
    }

    private ResultActions placeOrder(long userId) throws Exception {
        return mvc.perform(post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(userId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }
}
