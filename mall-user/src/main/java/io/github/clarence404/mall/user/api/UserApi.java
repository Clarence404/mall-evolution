package io.github.clarence404.mall.user.api;

import java.util.Optional;

/**
 * 用户模块对外接口。
 */
public interface UserApi {

    Optional<UserDTO> findById(long userId);
}
