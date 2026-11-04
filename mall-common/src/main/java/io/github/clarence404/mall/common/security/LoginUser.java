package io.github.clarence404.mall.common.security;

import java.security.Principal;

/** 登录态：认证过滤器校验访问令牌后放进安全上下文，Controller 用 {@code @AuthenticationPrincipal} 取。 */
public record LoginUser(long id) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(id);
    }
}
