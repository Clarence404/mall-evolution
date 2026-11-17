package io.github.clarence404.mall.perf.ha;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 实验用户的访问令牌：直接按服务端的规则签 HS256 JWT（iss、sub=用户 ID、roles=[USER]），不走登录接口。
 *
 * <p>为什么不登录：演示账号只有 8 个，而网关按「用户 + 路由」限流（下单每人每秒 1 次）、订单服务有下单频控（同一 SKU 待支付最多 2 单），
 * 几个账号压不出几十单每秒。实验用户的 ID 从 900000001 起（{@code ha.firstUserId}），不在 t_user 里，只用于下单、查订单这类
 * 只认令牌里用户 ID 的接口；「我的」页面会对它们返回 404，实验里不调。
 *
 * <p>密钥只能是 dev / demo 环境的本地占位密钥（application-dev.yml 里那串），由实验脚本从配置文件读出后用 {@code -Dha.jwtSecret} 传进来，
 * 这里不写死，也不会写进结果文件。
 */
public final class ExperimentTokens {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER =
            B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final SecretKeySpec key;
    private final String issuer;
    private final Duration ttl;

    public ExperimentTokens(String secret, String issuer, Duration ttl) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT 密钥至少 32 字节（与服务端 JwtProperties 的要求一致）");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.issuer = issuer;
        this.ttl = ttl;
    }

    /** 给一个用户签发访问令牌，有效期从现在起算。 */
    public String mint(long userId) {
        return mint(userId, Instant.now());
    }

    String mint(long userId, Instant now) {
        String payload = "{\"iss\":\"" + issuer + "\",\"sub\":\"" + userId + "\",\"roles\":[\"USER\"],\"iat\":"
                + now.getEpochSecond() + ",\"exp\":" + now.plus(ttl).getEpochSecond() + "}";
        String signingInput = HEADER + "." + B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + B64.encodeToString(sign(signingInput));
    }

    byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Gatling 的 feeder：在 [firstUserId, firstUserId + count) 里循环取用户，每条记录是 {@code userId}、{@code token}。
     * 令牌预先签好（几万个也只要几十毫秒），压测中不再算 HMAC。
     */
    public Iterator<Map<String, Object>> circularUsers(long firstUserId, int count) {
        String[] tokens = new String[count];
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            tokens[i] = mint(firstUserId + i, now);
        }
        AtomicLong next = new AtomicLong();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Map<String, Object> next() {
                int i = (int) (next.getAndIncrement() % count);
                return Map.of("userId", firstUserId + i, "token", tokens[i]);
            }
        };
    }
}
