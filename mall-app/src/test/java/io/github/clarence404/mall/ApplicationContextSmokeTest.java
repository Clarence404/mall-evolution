package io.github.clarence404.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.support.H2TestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 冒烟测试：整个 Spring 上下文能装配起来、Flyway 能执行；并把 springdoc 生成的 OpenAPI 导出到 target/openapi.json，
 * 供 CI 与仓库里的 api/openapi.json 做兼容性 diff。
 */
class ApplicationContextSmokeTest extends H2TestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void contextLoadsAndExportsOpenApi() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"operationId\":\"placeOrder\"", "\"operationId\":\"cancelOrder\"");
        Path out = Path.of("target", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
