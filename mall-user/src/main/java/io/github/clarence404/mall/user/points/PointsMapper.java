package io.github.clarence404.mall.user.points;

import io.github.clarence404.mall.common.id.Ids;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PointsMapper {

    default int add(long userId, int points, String orderNo) {
        return insert(Ids.next(), userId, points, orderNo);
    }

    @Insert("INSERT INTO t_points_log (id, user_id, points, order_no) VALUES (#{id}, #{userId}, #{points}, #{orderNo})")
    int insert(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("points") int points,
            @Param("orderNo") String orderNo);

    @Select("SELECT COALESCE(SUM(points), 0) FROM t_points_log WHERE user_id = #{userId}")
    long sumByUser(@Param("userId") long userId);
}
