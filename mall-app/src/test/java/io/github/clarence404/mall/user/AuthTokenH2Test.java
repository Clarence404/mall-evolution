package io.github.clarence404.mall.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.clarence404.mall.support.H2TestBase;
import io.github.clarence404.mall.user.auth.AuthService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 刷新令牌：刷新即轮换（旧的立刻作废），登出与踢下线可吊销。 */
class AuthTokenH2Test extends H2TestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    AuthService authService;

    @Test
    void refreshRotatesTokenAndOldRefreshTokenIsRejected() throws Exception {
        String r1 = login();

        String body =
                refresh(r1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String r2 = JsonPath.read(body, "$.refreshToken");
        assertThat(r2).isNotEqualTo(r1);
        assertThat((String) JsonPath.read(body, "$.accessToken")).isNotBlank();

        // 旧令牌已经轮换掉，再用就是 401；新令牌照常可用
        refresh(r1)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_REFRESH_TOKEN_INVALID"));
        refresh(r2).andExpect(status().isOk());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String r = login();

        mvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + r + "\"}"))
                .andExpect(status().isNoContent());

        refresh(r).andExpect(status().isUnauthorized());
    }

    @Test
    void kickOutRevokesAllRefreshTokensOfUser() throws Exception {
        String phone = login();
        String tablet = login();

        authService.kickOut(10001L);

        refresh(phone).andExpect(status().isUnauthorized());
        refresh(tablet).andExpect(status().isUnauthorized());
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000000\",\"password\":\"mall123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.refreshToken");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }
}
