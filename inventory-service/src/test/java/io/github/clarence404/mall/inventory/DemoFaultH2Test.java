package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.order.api.OrderQueryApi;
import org.junit.jupiter.api.AfterEach;
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

/** demo profile 下才有的故障注入开关：打开后 reserve 变慢，关掉恢复；库存查看接口给演示控制台用。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2", "demo"})
class DemoFaultH2Test {

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
        jdbc.update("INSERT INTO t_inventory (sku_id, stock, reserved) VALUES (20001, 5, 0)");
    }

    @AfterEach
    void clearFault() throws Exception {
        mvc.perform(delete("/internal/demo/fault")).andExpect(status().isOk());
    }

    @Test
    void reserveDelayToggle() throws Exception {
        mvc.perform(get("/internal/demo/fault"))
                .andExpect(jsonPath("$.reserveDelayMs").value(0));
        mvc.perform(put("/internal/demo/fault").param("reserveDelayMs", "300"))
                .andExpect(jsonPath("$.reserveDelayMs").value(300));

        long start = System.nanoTime();
        reserve("2001").andExpect(jsonPath("$").value("OK"));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);

        mvc.perform(delete("/internal/demo/fault"))
                .andExpect(jsonPath("$.reserveDelayMs").value(0));
        mvc.perform(put("/internal/demo/fault").param("reserveDelayMs", "99999"))
                .andExpect(jsonPath("$.reserveDelayMs").value(3000)); // 上限 3 秒
    }

    @Test
    void stocksForConsole() throws Exception {
        mvc.perform(get("/internal/demo/stocks").param("skuIds", "20001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stock").value(5));
    }

    private org.springframework.test.web.servlet.ResultActions reserve(String orderNo) throws Exception {
        return mvc.perform(post("/internal/inventory/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderNo\":\"" + orderNo + "\",\"lines\":[{\"skuId\":20001,\"quantity\":1}]}"));
    }
}
