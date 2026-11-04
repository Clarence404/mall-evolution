package io.github.clarence404.mall.user.domain;

import io.github.clarence404.mall.user.api.UserApi;
import io.github.clarence404.mall.user.api.UserDTO;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserQueryService implements UserApi {

    private final UserMapper userMapper;

    @Override
    public Optional<UserDTO> findById(long userId) {
        return Optional.ofNullable(userMapper.selectById(userId))
                .map(u -> new UserDTO(u.getId(), u.getPhone(), u.getNickname()));
    }
}
