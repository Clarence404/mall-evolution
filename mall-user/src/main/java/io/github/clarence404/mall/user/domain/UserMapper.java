package io.github.clarence404.mall.user.domain;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT id, phone, password_hash, nickname, status FROM t_user WHERE id = #{id}")
    User selectById(@Param("id") long id);

    @Select("SELECT id, phone, password_hash, nickname, status FROM t_user WHERE phone = #{phone}")
    User selectByPhone(@Param("phone") String phone);
}
