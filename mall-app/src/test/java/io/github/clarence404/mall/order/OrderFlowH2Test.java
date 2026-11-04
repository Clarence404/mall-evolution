package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.clarence404.mall.order.pay.MockChannelSigner;
import io.github.clarence404.mall.support.H2TestBase;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 主流程走真实的 MyBatis SQL（H2 的 MySQL 兼容模式）：登录、下单与幂等重放、列表、支付回调、取消与库存回补。 */
class OrderFlowH2Test extends H2TestBase {

    private static final String ADDRESS =
            "\"address\":{\"receiver\":\"张三\",\"phone\":\"13800000000\",\"detail\":\"杭州市西湖区\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockChannelSigner signer;

    String token;

    @BeforeEach
    void login() throws Exception {
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
        int stockBefore = stock(20002L);
        String key = UUID.randomUUID().toString();
        String body = "{\"lines\":[{\"skuId\":\"20002\",\"quantity\":2}]," + ADDRESS + "}";

        String orderNo = read(
                placeOrder(key, body)
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Idempotent-Replayed", "false"))
                        .andExpect(jsonPath("$.payAmount").value("25.60")),
                "$.orderNo");
        placeOrder(key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderNo").value(orderNo))
                .andExpect(jsonPath("$.items[0].productName").exists());
        assertThat(stock(20002L)).isEqualTo(stockBefore - 2);

        mvc.perform(get("/api/orders").param("size", "1").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderNo").value(orderNo));

        // 支付：建支付单，模拟渠道回调（带签名），订单 10 → 20
        String paymentNo = read(
                mvc.perform(post("/api/payments")
                                .header(HttpHeaders.AUTHORIZATION, token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"orderNo\":\"" + orderNo + "\"}"))
                        .andExpect(status().isCreated()),
                "$.paymentNo");
        String sign = signer.sign(paymentNo, "T-" + paymentNo, "25.60");
        String notify = "{\"paymentNo\":\"" + paymentNo + "\",\"channelTradeNo\":\"T-" + paymentNo
                + "\",\"amount\":\"25.60\",\"sign\":\"" + sign + "\"}";
        mvc.perform(post("/api/payments/notify/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notify))
                .andExpect(status().isOk());
        mvc.perform(post("/api/payments/notify/mock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notify))
                .andExpect(status().isOk()); // 渠道重发：幂等
        mvc.perform(get("/api/orders/{orderNo}", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.status").value("PENDING_SHIPMENT"))
                .andExpect(jsonPath("$.paidAt").exists());

        // 已支付的订单不能取消
        mvc.perform(post("/api/orders/{orderNo}/cancel", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));

        // 发货前改地址：只传要改的字段
        mvc.perform(patch("/api/orders/{orderNo}/address", orderNo)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"detail\":\"杭州市余杭区\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiver.address").value("杭州市余杭区"))
                .andExpect(jsonPath("$.receiver.name").value("张三"));
    }

    @Test
    void cancelRestoresStock() throws Exception {
        int before = stock(20001L);
        String orderNo = read(
                placeOrder(
                                UUID.randomUUID().toString(),
                                "{\"lines\":[{\"skuId\":\"20001\",\"quantity\":3}]," + ADDRESS + "}")
                        .andExpect(status().isCreated()),
                "$.orderNo");
        assertThat(stock(20001L)).isEqualTo(before - 3);

        mvc.perform(post("/api/orders/{orderNo}/cancel", orderNo).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stock(20001L)).isEqualTo(before);
    }

    @Test
    void offShelfAndInsufficientStockAreRejected() throws Exception {
        placeOrder(UUID.randomUUID().toString(), "{\"lines\":[{\"skuId\":\"20004\",\"quantity\":1}]," + ADDRESS + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_SKU_OFF_SHELF"));

        int before = stock(20003L);
        int before20001 = stock(20001L);
        jdbc.update("UPDATE t_inventory SET stock = 1 WHERE sku_id = 20003");
        placeOrder(
                        UUID.randomUUID().toString(),
                        "{\"lines\":[{\"skuId\":\"20001\",\"quantity\":1},{\"skuId\":\"20003\",\"quantity\":2}],"
                                + ADDRESS + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
        assertThat(stock(20003L)).isEqualTo(1);
        assertThat(stock(20001L)).isEqualTo(before20001); // 整个事务回滚，先扣的 20001 也还回来了
        jdbc.update("UPDATE t_inventory SET stock = ? WHERE sku_id = 20003", before);
    }

    @Test
    void otherUsersOrderLooksLikeNotFound() throws Exception {
        String orderNo = read(
                placeOrder(
                                UUID.randomUUID().toString(),
                                "{\"lines\":[{\"skuId\":\"20001\",\"quantity\":1}]," + ADDRESS + "}")
                        .andExpect(status().isCreated()),
                "$.orderNo");
        mvc.perform(get("/api/orders/{orderNo}", orderNo)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                io.github.clarence404.mall.support.TestTokens.bearer(10002L)))
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

    private int stock(long skuId) {
        return jdbc.queryForObject("SELECT stock FROM t_inventory WHERE sku_id = ?", Integer.class, skuId);
    }
}
