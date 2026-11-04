package io.github.clarence404.mall.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.clarence404.mall.support.H2TestBase;
import io.github.clarence404.mall.support.TestTokens;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 后台改价接口 /api/admin/** 只对 ADMIN 角色开放：普通用户 403，未登录 401。 */
class ProductAdminAuthorizationH2Test extends H2TestBase {

    private static final String BODY = "{\"price\":\"36.90\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void restorePrice() {
        jdbc.update("UPDATE t_product SET price = 9.90 WHERE id = 20004");
    }

    @Test
    void normalUserGets403OnAdminPriceChange() throws Exception {
        mvc.perform(put("/api/admin/products/20004")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/products/20004/cache-evict")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(10001L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousGets401OnAdminPriceChange() throws Exception {
        mvc.perform(put("/api/admin/products/20004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void opsAccountLoginCarriesAdminRoleAndCanChangePrice() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000009\",\"password\":\"mall123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String token = "Bearer " + JsonPath.read(body, "$.accessToken");

        mvc.perform(put("/api/admin/products/20004")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT price FROM t_product WHERE id = 20004", String.class))
                .isEqualTo("36.90");
    }
}
