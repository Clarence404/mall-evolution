package io.github.clarence404.mall.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.clarence404.mall.support.H2TestBase;
import io.github.clarence404.mall.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.accept.ApiVersionHolder;
import org.springframework.web.servlet.HandlerMapping;

/** 版本从请求头 API-Version 读：不带请求头按 1.0 处理，老客户端不用改；不认识的版本返回 400。 */
class ApiVersionH2Test extends H2TestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void missingApiVersionHeaderDefaultsToVersionOnePointZero() throws Exception {
        Object withoutHeader = mvc.perform(
                        get("/api/orders").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L)))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getAttribute(HandlerMapping.API_VERSION_ATTRIBUTE);
        Object explicitV1 = mvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                        .header("API-Version", "1.0"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getAttribute(HandlerMapping.API_VERSION_ATTRIBUTE);

        assertThat(version(withoutHeader)).isNotNull().isEqualTo(version(explicitV1));
    }

    private static Comparable<?> version(Object holder) {
        return ((ApiVersionHolder) holder).getVersion();
    }

    @Test
    void unsupportedApiVersionIsRejectedWith400() throws Exception {
        mvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                        .header("API-Version", "9.0"))
                .andExpect(status().isBadRequest());
    }
}
