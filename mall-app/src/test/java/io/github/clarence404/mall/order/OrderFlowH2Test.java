package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.clarence404.mall.common.remote.RemoteBizException;
import io.github.clarence404.mall.inventory.api.DeductStockRequest;
import io.github.clarence404.mall.inventory.api.RestoreStockRequest;
import io.github.clarence404.mall.inventory.api.StockLine;
import io.github.clarence404.mall.order.cancel.StockRestorer;
import io.github.clarence404.mall.support.H2TestBase;
import io.github.clarence404.mall.support.TestTokens;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

/**
 * 主流程走真实的 MyBatis SQL（H2 的 MySQL 兼容模式），库存服务用替身：登录、下单与幂等重放、列表、
 * 支付成功（集群内 markPaid）、取消、库存服务返回 STOCK_INSUFFICIENT 时原样透传。
 */
class OrderFlowH2Test extends H2TestBase {

    private static final String ADDRESS =
            "\"address\":{\"receiver\":\"张三\",\"phone\":\"13800000000\",\"detail\":\"杭州市西湖区\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StockRestorer stockRestorer;

    String token;

    @BeforeEach
    void login() throws Exception {
        reset(inventoryApi);
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
    void placeReplayListPayAndCancel() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"lines\":[{\"skuId\":\"20002\",\"quantity\":2}]," + ADDRESS + "}";

        String orderNo = read(
                placeOrder(key, body)
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Idempotent-Replayed", "false"))
                        .andExpect(jsonPath("$.payAmount").value("25.60")),
                "$.orderNo");
        assertThat(orderNo).hasSize(23); // 阶段三：雪花 ID + 用户 ID 低位
        verify(inventoryApi).deduct(new DeductStockRequest(orderNo, List.of(new StockLine(20002L, 2))));

        placeOrder(key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderNo").value(orderNo))
                .andExpect(jsonPath("$.items[0].productName").exists());
        verify(inventoryApi).deduct(any()); // 重放不再远程扣库存

        mvc.perform(get("/api/orders").param("size", "1").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderNo").value(orderNo));

        // 支付服务处理渠道回调后同步调用 markPaid（集群内接口），按订单号幂等
        String paid = "{\"paymentNo\":\"P1\",\"amount\":25.60,\"paidAt\":\"2026-10-01T10:00:00\"}";
        mvc.perform(post("/internal/orders/{orderNo}/paid", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("PAID"));
        mvc.perform(post("/internal/orders/{orderNo}/paid", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paid))
                .andExpect(jsonPath("$").value("ALREADY_PAID"));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE topic = 'order-paid' AND msg_key = ?",
                        Integer.class,
                        orderNo))
                .isEqualTo(1);

        mvc.perform(get("/internal/orders/{orderNo}", orderNo))
                .andExpect(jsonPath("$.status").value(20))
                .andExpect(jsonPath("$.userId").value(10001));

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
    void cancelRestoresStockRemotely() throws Exception {
        String orderNo = read(
                placeOrder(
                                UUID.randomUUID().toString(),
                                "{\"lines\":[{\"skuId\":\"20001\",\"quantity\":3}]," + ADDRESS + "}")
                        .andExpect(status().isCreated()),
                "$.orderNo");

        mvc.perform(post("/api/orders/{orderNo}/cancel", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(inventoryApi).restore(new RestoreStockRequest(orderNo, List.of(new StockLine(20001L, 3))));
        assertThat(restoreTasks(orderNo)).isZero(); // 归还成功，任务已删除
    }

    @Test
    void restoreFailureAfterCancelIsRetriedUntilInventoryRecovers() throws Exception {
        String orderNo = read(
                placeOrder(
                                UUID.randomUUID().toString(),
                                "{\"lines\":[{\"skuId\":\"20001\",\"quantity\":1}]," + ADDRESS + "}")
                        .andExpect(status().isCreated()),
                "$.orderNo");
        doThrow(new IllegalStateException("inventory-service 不可用"))
                .when(inventoryApi)
                .restore(any());

        mvc.perform(post("/api/orders/{orderNo}/cancel", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(restoreTasks(orderNo)).isEqualTo(1); // 关单照常成功，归还任务留在表里

        reset(inventoryApi); // 库存服务恢复
        jdbc.update("UPDATE t_stock_restore_task SET next_retry_at = NOW(3) WHERE order_no = ?", orderNo);
        stockRestorer.retryDue();

        verify(inventoryApi).restore(new RestoreStockRequest(orderNo, List.of(new StockLine(20001L, 1))));
        assertThat(restoreTasks(orderNo)).isZero();
    }

    private int restoreTasks(String orderNo) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_stock_restore_task WHERE order_no = ?", Integer.class, orderNo);
    }

    @Test
    void stockInsufficientFromInventoryServiceIsPassedThrough() throws Exception {
        ProblemDetail remote = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "SKU 20003 库存不足");
        remote.setType(URI.create("https://api.mall.example/problems/stock-insufficient"));
        remote.setTitle("库存不足");
        remote.setProperty("code", "STOCK_INSUFFICIENT");
        doThrow(new RemoteBizException(HttpStatus.CONFLICT, remote))
                .when(inventoryApi)
                .deduct(any());

        placeOrder(UUID.randomUUID().toString(), "{\"lines\":[{\"skuId\":\"20003\",\"quantity\":2}]," + ADDRESS + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.traceId").exists());
        verify(inventoryApi, never()).restore(any()); // 明确失败，什么都没扣
    }

    @Test
    void offShelfIsRejectedBeforeCallingInventory() throws Exception {
        placeOrder(UUID.randomUUID().toString(), "{\"lines\":[{\"skuId\":\"20004\",\"quantity\":1}]," + ADDRESS + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_SKU_OFF_SHELF"));
        verify(inventoryApi, never()).deduct(any());
    }

    @Test
    void otherUsersOrderLooksLikeNotFound() throws Exception {
        String orderNo = read(
                placeOrder(
                                UUID.randomUUID().toString(),
                                "{\"lines\":[{\"skuId\":\"20001\",\"quantity\":1}]," + ADDRESS + "}")
                        .andExpect(status().isCreated()),
                "$.orderNo");
        mvc.perform(get("/api/orders/{orderNo}", orderNo).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10002L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
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
