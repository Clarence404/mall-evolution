package io.github.clarence404.mall.order.place;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.common.security.SecurityConfig;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.order.query.OrderQueryService;
import io.github.clarence404.mall.support.TestTokens;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 不需要 Docker 的 Web 层测试：鉴权、请求头、校验分组、ProblemDetail 与重放响应头。 */
@WebMvcTest(PlaceOrderController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("it")
class PlaceOrderControllerTest {

    private static final String VALID_BODY = """
            {"lines":[{"skuId":"20001","quantity":2}],
             "address":{"receiver":"张三","phone":"13800000000","detail":"杭州市西湖区"}}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PlaceOrderService placeOrderService;

    @MockitoBean
    OrderQueryService orderQueryService;

    @Test
    void createdReturns201WithLocationAndReplayHeader() throws Exception {
        when(placeOrderService.place(anyLong(), anyString(), any())).thenReturn(PlaceOrderResult.created(order()));

        mvc.perform(placeOrder("0123456789abcdef-key", VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/orders/1849203385720832512"))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.orderNo").value("1849203385720832512"))
                .andExpect(jsonPath("$.payAmount").value("79.80"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void replayedReturnsSame201WithReplayHeader() throws Exception {
        Order order = order();
        when(placeOrderService.place(anyLong(), anyString(), any())).thenReturn(PlaceOrderResult.replayed(order));
        when(orderQueryService.getOwn(10001L, order.getOrderNo())).thenReturn(order);

        mvc.perform(placeOrder("0123456789abcdef-key", VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderNo").value(order.getOrderNo()));
    }

    @Test
    void missingIdempotencyKeyReturns400Problem() throws Exception {
        mvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(placeOrderService, never()).place(anyLong(), anyString(), any());
    }

    @Test
    void tooShortIdempotencyKeyReturns400() throws Exception {
        mvc.perform(placeOrder("short", VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void onCreateGroupRequiresReceiver() throws Exception {
        String body = """
                {"lines":[{"skuId":"20001","quantity":2}],
                 "address":{"phone":"13800000000","detail":"杭州市西湖区"}}
                """;
        mvc.perform(placeOrder("0123456789abcdef-key", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(placeOrderService, never()).place(anyLong(), anyString(), any());
    }

    @Test
    void defaultGroupConstraintsStillApply() throws Exception {
        String body = """
                {"lines":[{"skuId":"20001","quantity":2}],
                 "address":{"receiver":"张三","phone":"12345","detail":"杭州市西湖区"}}
                """;
        mvc.perform(placeOrder("0123456789abcdef-key", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void withoutTokenReturns401Problem() throws Exception {
        mvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "0123456789abcdef-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private static org.springframework.test.web.servlet.RequestBuilder placeOrder(String key, String body) {
        return post("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static Order order() {
        Order o = new Order();
        o.setId(1849203385720832512L);
        o.setOrderNo("1849203385720832512");
        o.setUserId(10001L);
        o.setStatus(OrderStatus.PENDING_PAYMENT);
        o.setTotalAmount(new BigDecimal("79.80"));
        o.setFreightAmount(BigDecimal.ZERO);
        o.setDiscountAmount(BigDecimal.ZERO);
        o.setPayAmount(new BigDecimal("79.80"));
        o.setReceiverName("张三");
        o.setReceiverPhone("13800000000");
        o.setReceiverAddr("杭州市西湖区");
        o.setRemark("");
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }
}
