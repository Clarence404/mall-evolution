package io.github.clarence404.mall.order.cancel;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** t_stock_restore_task：关单后待远程归还的库存，归还成功即删除。 */
@Mapper
public interface StockRestoreTaskMapper {

    record Task(String orderNo, String lines, int retryCount) {}

    /** 与订单状态更新同一个本地事务；提交后会先立即尝试一次，nextRetryAt 是定时重试最早接手的时间。 */
    @Insert("""
            INSERT INTO t_stock_restore_task (order_no, stock_lines, next_retry_at)
            VALUES (#{orderNo}, #{lines}, #{nextRetryAt})
            """)
    int insert(
            @Param("orderNo") String orderNo,
            @Param("lines") String lines,
            @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Select("""
            SELECT order_no, stock_lines, retry_count FROM t_stock_restore_task
            WHERE next_retry_at <= NOW(3) ORDER BY next_retry_at LIMIT #{limit}
            """)
    @org.apache.ibatis.annotations.ConstructorArgs({
        @org.apache.ibatis.annotations.Arg(column = "order_no", javaType = String.class),
        @org.apache.ibatis.annotations.Arg(column = "stock_lines", javaType = String.class),
        @org.apache.ibatis.annotations.Arg(column = "retry_count", javaType = int.class)
    })
    List<Task> selectDue(@Param("limit") int limit);

    @Delete("DELETE FROM t_stock_restore_task WHERE order_no = #{orderNo}")
    int delete(@Param("orderNo") String orderNo);

    /** 失败一次：retry_count + 1，推迟到 nextRetryAt（退避由调用方计算）。 */
    @Update("""
            UPDATE t_stock_restore_task SET next_retry_at = #{nextRetryAt}, retry_count = retry_count + 1
            WHERE order_no = #{orderNo}
            """)
    int markRetry(@Param("orderNo") String orderNo, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Select("SELECT COUNT(*) FROM t_stock_restore_task")
    long count();
}
