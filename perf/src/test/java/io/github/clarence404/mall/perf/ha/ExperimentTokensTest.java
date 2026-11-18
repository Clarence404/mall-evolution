package io.github.clarence404.mall.perf.ha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 实验令牌的格式与签名：头部 HS256、载荷里 iss / sub / roles / iat / exp 与服务端 AuthService 签发的一致，签名可以用同一把密钥验证。
 * （服务端用 Nimbus 校验 HS256 + issuer + 有效期，这里按 JWS 规范独立重算签名比对。）
 */
class ExperimentTokensTest {

    private static final String SECRET = "k".repeat(40); // 测试用的随便一串，够 32 字节即可
    private final ExperimentTokens tokens = new ExperimentTokens(SECRET, "mall-evolution", Duration.ofHours(2));

    @Test
    void claimsMatchWhatTheServerIssues() {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        String[] parts = tokens.mint(900000001L, now).split("\\.");

        assertThat(parts).hasSize(3);
        assertThat(decode(parts[0])).isEqualTo("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        assertThat(decode(parts[1]))
                .contains("\"iss\":\"mall-evolution\"")
                .contains("\"sub\":\"900000001\"")
                .contains("\"roles\":[\"USER\"]")
                .contains("\"iat\":" + now.getEpochSecond())
                .contains("\"exp\":" + now.plus(Duration.ofHours(2)).getEpochSecond());
    }

    @Test
    void signatureVerifiesWithTheSameKey() throws Exception {
        String token = tokens.mint(900000002L);
        int lastDot = token.lastIndexOf('.');
        byte[] expected = Base64.getUrlDecoder().decode(token.substring(lastDot + 1));

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] actual = mac.doFinal(token.substring(0, lastDot).getBytes(StandardCharsets.US_ASCII));

        assertThat(MessageDigest.isEqual(expected, actual)).isTrue();
    }

    @Test
    void circularFeederCyclesThroughUsers() {
        Iterator<Map<String, Object>> it = tokens.circularUsers(900000001L, 2);

        assertThat(it.next()).containsEntry("userId", 900000001L);
        assertThat(it.next()).containsEntry("userId", 900000002L);
        assertThat(it.next()).containsEntry("userId", 900000001L);
    }

    @Test
    void sequentialFeederNeverRepeatsAndSignsEachUser() {
        Iterator<Map<String, Object>> it = tokens.sequentialUsers(1_000_000_000L);

        Map<String, Object> first = it.next();
        Map<String, Object> second = it.next();

        assertThat(first).containsEntry("userId", 1_000_000_000L);
        assertThat(second).containsEntry("userId", 1_000_000_001L);
        assertThat(decode(((String) second.get("token")).split("\\.")[1])).contains("\"sub\":\"1000000001\"");
        assertThat(it.next()).containsEntry("userId", 1_000_000_002L);
    }

    @Test
    void firstUserIdByTimeMovesTenThousandIdsPerSecond() {
        long t = 1_795_000_100_000L;

        assertThat(ExperimentTokens.firstUserIdByTime(t)).isBetween(1_000_000_000L, 11_000_000_000L);
        assertThat(ExperimentTokens.firstUserIdByTime(t + 999)).isEqualTo(ExperimentTokens.firstUserIdByTime(t));
        assertThat(ExperimentTokens.firstUserIdByTime(t + 60_000) - ExperimentTokens.firstUserIdByTime(t))
                .isEqualTo(600_000); // 一分钟后起点往后挪 60 万个：每秒用掉不到 1 万个用户的压测，两轮不会重叠
    }

    @Test
    void adminTokenCarriesTheAdminRoleOnly() {
        String payload = decode(tokens.mintAdmin(900000000L).split("\\.")[1]);

        assertThat(payload).contains("\"sub\":\"900000000\"").contains("\"roles\":[\"ADMIN\"]");
    }

    @Test
    void shortSecretIsRejected() {
        assertThatThrownBy(() -> new ExperimentTokens("too-short", "mall-evolution", Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }
}
