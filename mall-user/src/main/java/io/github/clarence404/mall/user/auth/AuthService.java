package io.github.clarence404.mall.user.auth;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.common.security.JwtProperties;
import io.github.clarence404.mall.common.security.LoginUserAuthentication;
import io.github.clarence404.mall.user.api.UserErrorCode;
import io.github.clarence404.mall.user.domain.User;
import io.github.clarence404.mall.user.domain.UserMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 短期 JWT + 可吊销的刷新令牌：访问令牌 30 分钟无状态校验；刷新令牌 14 天，服务端只存 SHA-256 哈希。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties props;

    @Transactional
    public TokenPair login(String phone, String password) {
        User user = userMapper.selectByPhone(phone);
        if (user == null
                || user.getStatus() != User.ACTIVE
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(UserErrorCode.USER_LOGIN_FAILED);
        }
        return issue(user);
    }

    /** 刷新时轮换：旧刷新令牌作废，签发新的一对；角色按库里当前的值重新签，已禁用的用户刷新失败。 */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        String hash = sha256(refreshToken);
        Long userId = refreshTokenMapper.selectValidUserId(hash);
        if (userId == null || refreshTokenMapper.deleteByHash(hash) == 0) {
            throw new BizException(UserErrorCode.USER_REFRESH_TOKEN_INVALID);
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != User.ACTIVE) {
            throw new BizException(UserErrorCode.USER_REFRESH_TOKEN_INVALID);
        }
        return issue(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenMapper.deleteByHash(sha256(refreshToken));
    }

    @Transactional
    public void kickOut(long userId) {
        refreshTokenMapper.deleteByUser(userId);
    }

    /** 访问令牌：sub 是用户 ID，roles 是角色（后台接口要求 ADMIN）。 */
    public String issueAccessToken(long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(userId))
                .claim(LoginUserAuthentication.ROLES_CLAIM, List.of(role == null ? User.ROLE_USER : role))
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private TokenPair issue(User user) {
        long userId = user.getId();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        refreshTokenMapper.insert(
                Ids.next(), userId, sha256(refreshToken), LocalDateTime.now().plus(props.refreshTokenTtl()));
        return new TokenPair(
                issueAccessToken(userId, user.getRole()),
                refreshToken,
                props.accessTokenTtl().toSeconds());
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn) {}
}
