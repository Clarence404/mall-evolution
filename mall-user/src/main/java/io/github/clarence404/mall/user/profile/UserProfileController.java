package io.github.clarence404.mall.user.profile;

import io.github.clarence404.mall.common.degrade.DegradeProperties;
import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.error.CommonErrorCode;
import io.github.clarence404.mall.common.security.LoginUser;
import io.github.clarence404.mall.user.domain.User;
import io.github.clarence404.mall.user.domain.UserMapper;
import io.github.clarence404.mall.user.points.PointsMapper;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我的」页面。降级开关 mall.degrade.points-display-enabled 关闭后不再实时查积分，points 返回 null，前端隐藏该栏。
 */
@RestController
@RequiredArgsConstructor
class UserProfileController {

    private final UserMapper userMapper;
    private final PointsMapper pointsMapper;
    private final DegradeProperties degrade;

    @Operation(operationId = "getMyProfile")
    @GetMapping("/api/users/me")
    Profile me(@AuthenticationPrincipal LoginUser user) {
        User u = userMapper.selectById(user.id());
        if (u == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        Long points = degrade.isPointsDisplayEnabled() ? pointsMapper.sumByUser(u.getId()) : null;
        return new Profile(String.valueOf(u.getId()), u.getNickname(), points);
    }

    record Profile(String userId, String nickname, Long points) {}
}
