package io.github.clarence404.mall.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置：访问令牌 30 分钟，刷新令牌 14 天（服务端只存哈希）。密钥由环境变量注入，至少 32 字节。
 */
@ConfigurationProperties(prefix = "mall.security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl, Duration refreshTokenTtl) {

    public JwtProperties {
        if (issuer == null) {
            issuer = "mall-evolution";
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(30);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(14);
        }
    }

    public SecretKey secretKey() {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("mall.security.jwt.secret 至少 32 字节");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
