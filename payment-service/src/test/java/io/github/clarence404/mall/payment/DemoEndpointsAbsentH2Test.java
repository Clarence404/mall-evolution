package io.github.clarence404.mall.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.order.api.OrderQueryApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 不带 demo profile 时，故障注入、模拟 SIGTERM、降级开关这些演示接口都不存在（404），不会被误用。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2"})
class DemoEndpointsAbsentH2Test {

    @Autowired
    MockMvc mvc;

    /** 和本服务其他 H2 测试声明同样的替身，复用同一个测试上下文。 */
    @MockitoBean
    OrderQueryApi orderQueryApi;

    @Test
    void demoEndpointsAreNotRegistered() throws Exception {
        mvc.perform(get("/internal/demo/faults")).andExpect(status().isNotFound());
        mvc.perform(put("/internal/demo/faults/x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"/internal/payments/*\",\"failPercent\":100}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/internal/demo/lifecycle/exit")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/demo/degrade")).andExpect(status().isNotFound());
    }
}
