package io.github.clarence404.mall.common.security;

import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** 把校验通过的 JWT 转成以 {@link LoginUser} 为 principal 的认证对象；sub 即用户 ID，roles 声明转成 ROLE_ 权限。 */
public class LoginUserAuthentication extends AbstractAuthenticationToken {

    public static final String ROLES_CLAIM = "roles";

    private final LoginUser user;
    private final transient Jwt jwt;

    public LoginUserAuthentication(LoginUser user, Jwt jwt) {
        this(user, jwt, List.of());
    }

    public LoginUserAuthentication(LoginUser user, Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
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
        return jwt -> {
            List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
            List<GrantedAuthority> authorities = roles == null
                    ? List.of()
                    : roles.stream()
                            .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                            .toList();
            return new LoginUserAuthentication(new LoginUser(Long.parseLong(jwt.getSubject())), jwt, authorities);
        };
    }
}
