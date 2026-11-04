package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.clarence404.mall.common.remote.RemoteBizException;
import io.github.clarence404.mall.inventory.api.ReleaseStockRequest;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.api.ReserveStockRequest;
import io.github.clarence404.mall.order.paid.OrderPaidTxService;
import io.github.clarence404.mall.order.paid.PaymentSucceeded;
import io.github.clarence404.mall.payment.api.ClosePaymentResult;
import io.github.clarence404.mall.support.H2TestBase;
import io.github.clarence404.mall.support.TestTokens;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 主流程走真实的 MyBatis SQL（H2 的 MySQL 兼容模式），库存与支付服务用替身：预占下单与幂等重放、列表、
 * payment-succeeded 消费、取消（先关支付单再写 order-cancelled）、库存不足透传、预占结果未知时立即释放。
 */
class OrderFlowH2Test extends H2TestBase {

    private static final String ADDRESS =
            "\"address\":{\"receiver\":\"张三\",\"phone\":\"13800000000\",\"detail\":\"杭州市西湖区\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OrderPaidTxService orderPaidTxService;

    String token;

    @BeforeEach
    void login() throws Exception {
        when(inventoryApi.reserve(any())).thenReturn(ReserveResult.OK);
        when(paymentApi.close(any())).thenReturn(ClosePaymentResult.CLOSED);
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000000\",\"password\":\"mall123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        token = "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    @Test
    void reserveThenPlaceReplayListAndPay() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"lines\":[{\"skuId\":\"20002\",\"quantity\":2}]," + ADDRESS + "}";

        String orderNo = read(
                placeOrder(key, body)
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Idempotent-Replayed", "false"))
                        .andExpect(jsonPath("$.payAmount").value("25.60")),
                "$.orderNo");
        assertThat(orderNo).hasSize(23);
        verify(inventoryApi).reserve(new ReserveStockRequest(orderNo, List.of(new ReserveLine(20002L, 2)), 15));

        placeOrder(key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderNo").value(orderNo));
        verify(inventoryApi).reserve(any()); // 重放不再预占

        mvc.perform(get("/api/orders").param("size", "1").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.items[0].orderNo").value(orderNo));

        // payment-succeeded → 订单待发货 + order-paid（去重表 + 条件更新，重复消息无副作用）
        PaymentSucceeded msg =
                new PaymentSucceeded(orderNo, "P-" + orderNo, 10001L, new BigDecimal("25.60"), LocalDateTime.now());
        orderPaidTxService.markPaid(msg);
        orderPaidTxService.markPaid(msg);
        mvc.perform(get("/api/orders/{orderNo}", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.status").value("PENDING_SHIPMENT"));
        assertThat(outboxCount("order-paid", orderNo)).isEqualTo(1);

        mvc.perform(post("/api/orders/{orderNo}/cancel", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));

        mvc.perform(patch("/api/orders/{orderNo}/address", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"detail\":\"杭州市余杭区\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiver.address").value("杭州市余杭区"));
    }

    @Test
    void cancelClosesPaymentThenPublishesOrderCancelled() throws Exception {
        String orderNo = placeSimpleOrder();

        mvc.perform(post("/api/orders/{orderNo}/cancel", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(paymentApi).close(orderNo);
        assertThat(outboxCount("order-cancelled", orderNo)).isEqualTo(1); // 库存由 inventory-release-consumer 释放
        verify(inventoryApi, never()).release(any());
    }

    @Test
    void cancelIsRefusedWhenUserHasJustPaid() throws Exception {
        String orderNo = placeSimpleOrder();
        when(paymentApi.close(orderNo)).thenReturn(ClosePaymentResult.PAID);

        mvc.perform(post("/api/orders/{orderNo}/cancel", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict());
        assertThat(outboxCount("order-cancelled", orderNo)).isZero();
    }

    @Test
    void paymentArrivingAfterCancelAsksForRefund() throws Exception {
        String orderNo = placeSimpleOrder();
        mvc.perform(post("/api/orders/{orderNo}/cancel", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        orderPaidTxService.markPaid(
                new PaymentSucceeded(orderNo, "P9-" + orderNo, 10001L, BigDecimal.TEN, LocalDateTime.now()));

        assertThat(outboxCount("order-refund-required", "P9-" + orderNo)).isEqualTo(1);
    }

    @Test
    void secondPaymentOfSameOrderIsRefundedAndFirstOneStays() throws Exception {
        String orderNo = placeSimpleOrder();
        PaymentSucceeded first =
                new PaymentSucceeded(orderNo, "PA-" + orderNo, 10001L, new BigDecimal("39.90"), LocalDateTime.now());
        PaymentSucceeded second =
                new PaymentSucceeded(orderNo, "PB-" + orderNo, 10001L, new BigDecimal("39.90"), LocalDateTime.now());

        orderPaidTxService.markPaid(first);
        orderPaidTxService.markPaid(second);
        orderPaidTxService.markPaid(second); // 重复消息仍幂等

        mvc.perform(get("/api/orders/{orderNo}", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.status").value("PENDING_SHIPMENT"));
        assertThat(outboxCount("order-paid", orderNo)).isEqualTo(1); // 第一笔入账
        assertThat(outboxCount("order-refund-required", "PB-" + orderNo)).isEqualTo(1); // 第二笔退款
        assertThat(outboxCount("order-refund-required", "PA-" + orderNo)).isZero();
    }

    @Test
    void stockInsufficientIsPassedThroughAndNothingReleased() throws Exception {
        ProblemDetail remote = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "SKU 20003 库存不足");
        remote.setType(URI.create("https://api.mall.example/problems/stock-insufficient"));
        remote.setTitle("库存不足");
        remote.setProperty("code", "STOCK_INSUFFICIENT");
        doThrow(new RemoteBizException(HttpStatus.CONFLICT, remote))
                .when(inventoryApi)
                .reserve(any());

        placeOrder(UUID.randomUUID().toString(), "{\"lines\":[{\"skuId\":\"20003\",\"quantity\":2}]," + ADDRESS + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
        verify(inventoryApi, never()).release(any());
    }

    @Test
    void unknownReserveResultReleasesAndReturnsSystemError() throws Exception {
        doThrow(new ResourceAccessException("read timeout")).when(inventoryApi).reserve(any());

        placeOrder(UUID.randomUUID().toString(), "{\"lines\":[{\"skuId\":\"20001\",\"quantity\":1}]," + ADDRESS + "}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));
        verify(inventoryApi).release(any(ReleaseStockRequest.class));
    }

    @Test
    void serverErrorFromInventoryIsUnknownSoReservationIsReleased() throws Exception {
        doThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null))
                .when(inventoryApi)
                .reserve(any());

        placeOrder(UUID.randomUUID().toString(), "{\"lines\":[{\"skuId\":\"20001\",\"quantity\":1}]," + ADDRESS + "}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));
        verify(inventoryApi).release(any(ReleaseStockRequest.class)); // 5xx 结果未知：立即释放，不等过期回查
    }

    @Test
    void otherUsersOrderLooksLikeNotFound() throws Exception {
        String orderNo = placeSimpleOrder();
        mvc.perform(get("/api/orders/{orderNo}", orderNo).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10002L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private String placeSimpleOrder() throws Exception {
        return read(
                placeOrder(
                                UUID.randomUUID().toString(),
                                "{\"lines\":[{\"skuId\":\"20001\",\"quantity\":1}]," + ADDRESS + "}")
                        .andExpect(status().isCreated()),
                "$.orderNo");
    }

    private int outboxCount(String topic, String orderNo) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_outbox WHERE topic = ? AND msg_key = ?", Integer.class, topic, orderNo);
    }

    private ResultActions placeOrder(String key, String body) throws Exception {
        return mvc.perform(post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String read(ResultActions actions, String path) throws Exception {
        return JsonPath.read(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }
}
