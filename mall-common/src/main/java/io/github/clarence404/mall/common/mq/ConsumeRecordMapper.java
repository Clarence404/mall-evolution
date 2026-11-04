package io.github.clarence404.mall.common.mq;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 消费去重记录 t_consume_record：主键 (consumer_group, biz_key)，biz_key 即消息 Keys（order_no）。
 *
 * <p>放在 mall-common：积分在 mall-user、通知与发货单在 mall-order，都要用同一张去重表，而 mall-user 不能依赖 mall-order。
 */
@Mapper
public interface ConsumeRecordMapper {

    /** 返回 0 说明这个组已经处理过这条消息。与业务写入放在同一个本地事务里。 */
    @Insert("INSERT IGNORE INTO t_consume_record (consumer_group, biz_key) VALUES (#{group}, #{bizKey})")
    int insertIgnore(@Param("group") String group, @Param("bizKey") String bizKey);

    @Select("SELECT COUNT(*) > 0 FROM t_consume_record WHERE consumer_group = #{group} AND biz_key = #{bizKey}")
    boolean exists(@Param("group") String group, @Param("bizKey") String bizKey);

    /** 保留 30 天，分批删除。 */
    @Delete("DELETE FROM t_consume_record WHERE created_at < #{before} LIMIT #{limit}")
    int deleteBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
