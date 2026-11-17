package io.github.clarence404.mall.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.order.api.OrderQueryApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * demo profile 下支付服务也有 mall-common 的通用故障注入（重试风暴实验注入在支付查询上）：规则对真实接口生效，撤销后恢复；
 * 参数不合法返回 400。生产不注册，见 {@link DemoEndpointsAbsentH2Test}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2", "demo"})
class DemoFaultsH2Test {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderQueryApi orderQueryApi;

    @AfterEach
    void clear() throws Exception {
        mvc.perform(delete("/internal/demo/faults")).andExpect(status().isNoContent());
    }

    @Test
    void injectedFailureHitsRealEndpointUntilRemoved() throws Exception {
        mvc.perform(get("/internal/payments/9001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"));

        mvc.perform(put("/internal/demo/faults/pay-query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"GET\",\"path\":\"/internal/payments/*\",\"failPercent\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failStatus").value(503))
                .andExpect(jsonPath("$.expiresAt").exists());

        mvc.perform(get("/internal/payments/9001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Demo-Fault", "pay-query"))
                .andExpect(jsonPath("$.code").value("DEMO_FAULT"));
        // 列表里能看到这条规则（实验脚本把它写进记录）
        mvc.perform(get("/internal/demo/faults"))
                .andExpect(jsonPath("$[0].name").value("pay-query"));

        mvc.perform(delete("/internal/demo/faults/pay-query")).andExpect(status().isNoContent());
        mvc.perform(get("/internal/payments/9001")).andExpect(status().isOk());
    }

    @Test
    void invalidRuleIsRejected() throws Exception {
        mvc.perform(put("/internal/demo/faults/bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/internal/payments/*\",\"delayMs\":999999}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/demo/faults"))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
