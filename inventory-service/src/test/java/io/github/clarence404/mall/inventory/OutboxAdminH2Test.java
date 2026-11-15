package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 失败记录重投接口在库存服务里同样生效：接口在 mall-common，这里只确认它被扫描到、鉴权规则一致、
 * 操作的是本服务自己的 t_outbox（含新加的 retry_since 列，即本服务的 Flyway 迁移已执行）。
 * 重投的各种细节（批量上限、放弃计时、被中继重新发出）在 mall-app 的 OutboxRedriveH2Test 与 OutboxRelayIT 里覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2"})
class OutboxAdminH2Test {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** 和本服务其他 H2 测试声明同样的替身，Spring 才能复用同一个测试上下文，不用再启动一遍。 */
    @MockitoBean
    OrderQueryApi orderQueryApi;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM t_outbox");
        // 一条 30 小时前创建、已置失败的记录，一条待发送的记录
        jdbc.update("INSERT INTO t_outbox (id, topic, msg_key, payload, status, retry_count, next_retry_at, created_at)"
                + " VALUES (1001, 'inventory-reserve-expired', 'K1', '{}', 2, 40,"
                + " DATEADD('HOUR', -30, CURRENT_TIMESTAMP(3)), DATEADD('HOUR', -30, CURRENT_TIMESTAMP(3)))");
        jdbc.update("INSERT INTO t_outbox (id, topic, msg_key, payload, status, retry_count, next_retry_at)"
                + " VALUES (1002, 'inventory-reserve-expired', 'K2', '{}', 0, 3, CURRENT_TIMESTAMP(3))");
    }

    @Test
    void adminCanListAndRedriveThisServicesOwnFailedRecords() throws Exception {
        mvc.perform(get("/api/admin/outbox/failed").header(HttpHeaders.AUTHORIZATION, bearer(10009L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("1001"))
                .andExpect(jsonPath("$.items[0].payload").doesNotExist());

        mvc.perform(post("/api/admin/outbox/failed/redrive").header(HttpHeaders.AUTHORIZATION, bearer(10009L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(1))
                .andExpect(jsonPath("$.remaining").value(0));

        // 失败的那条回到待发送、计数清零、记下重投时刻；原本待发送的那条没被动过
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE id = 1001 AND status = 0 AND retry_count = 0"
                                + " AND retry_since IS NOT NULL",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE id = 1002 AND status = 0 AND retry_count = 3"
                                + " AND retry_since IS NULL",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void normalUserGets403AndAnonymousGets401() throws Exception {
        mvc.perform(post("/api/admin/outbox/failed/redrive").header(HttpHeaders.AUTHORIZATION, bearer(10001L, "USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", 1001L)
                        .header(HttpHeaders.AUTHORIZATION, bearer(10001L, "USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/outbox/failed")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/outbox/failed/redrive")).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT status FROM t_outbox WHERE id = 1001", Integer.class))
                .isEqualTo(2);
    }

    /** 测试用访问令牌：密钥与 application-it.yml 里的 mall.security.jwt.secret 一致，roles 声明决定是不是运营账号。 */
    private static String bearer(long userId, String role) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "test-only-jwt-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("mall-evolution")
                .subject(String.valueOf(userId))
                .claim("roles", List.of(role))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(1800))
                .build();
        return "Bearer "
                + encoder.encode(JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                        .getTokenValue();
    }
}
