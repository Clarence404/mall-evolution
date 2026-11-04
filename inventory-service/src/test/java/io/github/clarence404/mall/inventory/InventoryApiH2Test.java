package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 库存服务的集群内接口（路径与参数注解继承自 InventoryApi），H2 MySQL 模式跑真实 SQL，不需要 Docker。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2"})
class InventoryApiH2Test {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM t_inventory");
        jdbc.update("DELETE FROM t_stock_restore_log");
        jdbc.update("INSERT INTO t_inventory (sku_id, stock) VALUES (20001, 5), (20002, 1)");
    }

    @Test
    void deductAndRestoreThroughInheritedMappings() throws Exception {
        mvc.perform(post("/internal/inventory/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"1001\",\"lines\":[{\"skuId\":20002,\"quantity\":1},"
                                + "{\"skuId\":20001,\"quantity\":2}]}"))
                .andExpect(status().isOk());
        assertThat(stock(20001)).isEqualTo(3);
        assertThat(stock(20002)).isZero();

        mvc.perform(get("/internal/inventory/stocks").param("skuIds", "20001", "20002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(post("/internal/inventory/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"1001\",\"lines\":[{\"skuId\":20001,\"quantity\":2}]}"))
                .andExpect(status().isOk());
        assertThat(stock(20001)).isEqualTo(5);
    }

    @Test
    void restoreIsIdempotentPerOrderNo() throws Exception {
        String body = "{\"orderNo\":\"2001\",\"lines\":[{\"skuId\":20001,\"quantity\":2}]}";
        for (int i = 0; i < 3; i++) { // 调用方超时重试三次
            mvc.perform(post("/internal/inventory/restore")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
        assertThat(stock(20001)).isEqualTo(7); // 只加了一次
    }

    @Test
    void insufficientReturns409ProblemAndRollsBackEarlierLines() throws Exception {
        mvc.perform(post("/internal/inventory/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"1002\",\"lines\":[{\"skuId\":20001,\"quantity\":1},"
                                + "{\"skuId\":20002,\"quantity\":2}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"));
        assertThat(stock(20001)).isEqualTo(5); // 先扣的 20001 随事务回滚
    }

    private int stock(long skuId) {
        return jdbc.queryForObject("SELECT stock FROM t_inventory WHERE sku_id = ?", Integer.class, skuId);
    }
}
