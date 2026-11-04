package io.github.clarence404.mall.user.auth;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefreshTokenMapper {

    @Insert(
            "INSERT INTO t_refresh_token (id, user_id, token_hash, expire_at) VALUES (#{id}, #{userId}, #{tokenHash}, #{expireAt})")
    int insert(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("tokenHash") String tokenHash,
            @Param("expireAt") LocalDateTime expireAt);

    /** 返回令牌所属用户；过期或不存在返回 null。 */
    @Select("SELECT user_id FROM t_refresh_token WHERE token_hash = #{tokenHash} AND expire_at > NOW(3)")
    Long selectValidUserId(@Param("tokenHash") String tokenHash);

    @Delete("DELETE FROM t_refresh_token WHERE token_hash = #{tokenHash}")
    int deleteByHash(@Param("tokenHash") String tokenHash);

    /** 改密码、踢下线：删掉该用户全部刷新令牌，访问令牌最多再活 30 分钟。 */
    @Delete("DELETE FROM t_refresh_token WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") long userId);
}
