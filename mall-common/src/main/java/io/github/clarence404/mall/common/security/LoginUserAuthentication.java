package io.github.clarence404.mall.common.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/** 把校验通过的 JWT 转成以 {@link LoginUser} 为 principal 的认证对象；sub 即用户 ID。 */
public class LoginUserAuthentication extends AbstractAuthenticationToken {

    private final LoginUser user;
    private final transient Jwt jwt;

    public LoginUserAuthentication(LoginUser user, Jwt jwt) {
        super(List.of());
        this.user = user;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public LoginUser getPrincipal() {
        return user;
    }

    public static Converter<Jwt, LoginUserAuthentication> converter() {
        return jwt -> new LoginUserAuthentication(new LoginUser(Long.parseLong(jwt.getSubject())), jwt);
    }
}
