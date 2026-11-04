package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.order.api.OrderQueryApi;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 库存预占的三个接口（路径与参数注解继承自 InventoryApi），H2 MySQL 模式跑真实 SQL，不需要 Docker：
 * 正常的 reserve → confirm / release、幂等、空回滚与悬挂。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2"})
class InventoryApiH2Test {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OrderQueryApi orderQueryApi;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM t_inventory");
        jdbc.update("DELETE FROM t_inventory_reservation");
        jdbc.update("INSERT INTO t_inventory (sku_id, stock, reserved) VALUES (20001, 5, 0), (20002, 1, 0)");
    }

    @Test
    void reserveIsIdempotentAndConfirmMovesReservedOut() throws Exception {
        String body =
                "{\"orderNo\":\"1001\",\"lines\":[{\"skuId\":20002,\"quantity\":1},{\"skuId\":20001,\"quantity\":2}]}";
        post("/internal/inventory/reserve", body).andExpect(jsonPath("$").value("OK"));
        post("/internal/inventory/reserve", body).andExpect(jsonPath("$").value("OK")); // 重复请求
        assertThat(stock(20001)).containsEntry("stock", 3).containsEntry("reserved", 2);
        assertThat(stock(20002)).containsEntry("stock", 0).containsEntry("reserved", 1);

        post("/internal/inventory/confirm", "{\"orderNo\":\"1001\"}").andExpect(status().isOk());
        post("/internal/inventory/confirm", "{\"orderNo\":\"1001\"}").andExpect(status().isOk()); // 重复消息
        assertThat(stock(20001)).containsEntry("stock", 3).containsEntry("reserved", 0);
        assertThat(statuses("1001")).containsOnly(2);
    }

    @Test
    void releaseReturnsStockOnce() throws Exception {
        String body = "{\"orderNo\":\"1002\",\"lines\":[{\"skuId\":20001,\"quantity\":2}]}";
        post("/internal/inventory/reserve", body).andExpect(jsonPath("$").value("OK"));
        post("/internal/inventory/release", body).andExpect(status().isOk());
        post("/internal/inventory/release", body).andExpect(status().isOk()); // 重复释放
        assertThat(stock(20001)).containsEntry("stock", 5).containsEntry("reserved", 0);
        assertThat(statuses("1002")).containsOnly(3);
    }

    @Test
    void releaseBeforeReserveLeavesPlaceholderThatRejectsLateReserve() throws Exception {
        String body = "{\"orderNo\":\"1003\",\"lines\":[{\"skuId\":20001,\"quantity\":2}]}";
        // 空回滚：reserve 超时、订单服务立即 release，此时库里还没有预占记录
        post("/internal/inventory/release", body).andExpect(status().isOk());
        assertThat(statuses("1003")).containsOnly(3);
        assertThat(stock(20001)).containsEntry("stock", 5);

        // 悬挂：迟到的 reserve 查到 RELEASED 占位，返回 REJECTED，不扣库存
        post("/internal/inventory/reserve", body).andExpect(jsonPath("$").value("REJECTED"));
        assertThat(stock(20001)).containsEntry("stock", 5).containsEntry("reserved", 0);
    }

    @Test
    void insufficientStockIs409AndLeavesNoReservation() throws Exception {
        post(
                        "/internal/inventory/reserve",
                        "{\"orderNo\":\"1004\",\"lines\":[{\"skuId\":20001,\"quantity\":1},{\"skuId\":20002,\"quantity\":2}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
        assertThat(stock(20001)).containsEntry("stock", 5).containsEntry("reserved", 0);
        assertThat(statuses("1004")).isEmpty();
    }

    @Test
    void stocksForProductPage() throws Exception {
        mvc.perform(get("/internal/inventory/stocks").param("skuIds", "20001", "20002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private org.springframework.test.web.servlet.ResultActions post(String uri, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Map<String, Object> stock(long skuId) {
        return jdbc.queryForMap("SELECT stock, reserved FROM t_inventory WHERE sku_id = ?", skuId);
    }

    private java.util.List<Integer> statuses(String orderNo) {
        return jdbc.queryForList(
                "SELECT status FROM t_inventory_reservation WHERE order_no = ?", Integer.class, orderNo);
    }
}
