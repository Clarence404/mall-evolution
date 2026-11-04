package io.github.clarence404.mall.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 测试用访问令牌：密钥与 application-it.yml 里的 mall.security.jwt.secret 一致。 */
public final class TestTokens {

    public static final String SECRET = "test-only-jwt-secret-0123456789abcdef";

    private static final NimbusJwtEncoder ENCODER = new NimbusJwtEncoder(
            new ImmutableSecret<>(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

    private TestTokens() {}

    public static String bearer(long userId) {
        return bearer(userId, "USER");
    }

    /** 带角色的访问令牌，如 {@code bearer(10009, "ADMIN")}。 */
    public static String bearer(long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("mall-evolution")
                .subject(String.valueOf(userId))
                .claim("roles", List.of(role))
                .issuedAt(now)
                .expiresAt(now.plus(30, ChronoUnit.MINUTES))
                .build();
        String token = ENCODER.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return "Bearer " + token;
    }
}
