package io.github.clarence404.mall.common.outbox;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OutboxMapper {

    String COLUMNS = "id, topic, msg_key, payload, trace_parent, deliver_at, status, retry_count, next_retry_at";

    /** uk_topic_key：一笔订单的同一种消息只写一条。 */
    @Insert("""
            INSERT INTO t_outbox (id, topic, msg_key, payload, trace_parent, deliver_at, status, retry_count, next_retry_at)
            VALUES (#{id}, #{topic}, #{msgKey}, #{payload}, #{traceParent}, #{deliverAt}, 0, 0, #{nextRetryAt})
            """)
    int insert(OutboxRecord record);

    /**
     * 提交后立即发送的那一条；两台应用机并发时 SKIP LOCKED 让同一行只被一个线程处理。
     * 刚写入的记录 next_retry_at 是 3 秒后；已被扫表认领（租约 2 分钟）的不再认领。
     */
    @Select("SELECT " + COLUMNS + " FROM t_outbox"
            + " WHERE id = #{id} AND status = 0 AND next_retry_at <= DATE_ADD(NOW(3), INTERVAL 5 SECOND)"
            + " FOR UPDATE SKIP LOCKED")
    OutboxRecord selectPendingForUpdate(@Param("id") long id);

    /** 扫表兜底：到了 next_retry_at 仍未发送的记录。 */
    @Select("""
            SELECT id, topic, msg_key, payload, trace_parent, deliver_at, status, retry_count, next_retry_at
            FROM t_outbox WHERE status = 0 AND next_retry_at <= NOW(3)
            ORDER BY next_retry_at LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxRecord> selectDueForUpdate(@Param("limit") int limit);

    /** 认领：在加锁查询的同一个短事务里把 next_retry_at 推后作为租约，提交后别的实例扫表也看不到这批记录。 */
    @Update("""
            <script>
            UPDATE t_outbox SET next_retry_at = DATE_ADD(NOW(3), INTERVAL #{seconds} SECOND)
            WHERE id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int lease(@Param("ids") List<Long> ids, @Param("seconds") int seconds);

    /** 本批发送中途 Broker 出错：没轮到的记录放回去，下一轮立即可再认领。 */
    @Update("""
            <script>
            UPDATE t_outbox SET next_retry_at = NOW(3)
            WHERE status = 0 AND id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int releaseLease(@Param("ids") List<Long> ids);

    @Update("UPDATE t_outbox SET status = 1 WHERE id = #{id}")
    int markSent(@Param("id") long id);

    /**
     * 发送失败后的回写：retry_count + 1，next_retry_at 推后 backoffSeconds 秒（退避由 {@link OutboxRelay#backoffSeconds} 算好传进来）。
     *
     * <p>按时间封顶，不按次数：记录创建后 giveUpSeconds 秒内一直重试；超过这个时间还没发出去才置为失败（status = 2），
     * 交给 OutboxFailedRecords 告警与人工处理。原先「第 10 次失败就置失败」约 9 分钟就放弃，Broker 抖动十来分钟就会留下一批死记录。
     *
     * <p>用 TIMESTAMPADD / TIMESTAMPDIFF 而不是 DATE_ADD … INTERVAL：两者 MySQL 都支持，前者 H2 的 MySQL 模式也认，
     * 这条 SQL 才能在不需要 Docker 的测试里跑到。
     */
    @Update("""
            UPDATE t_outbox SET
                status = CASE WHEN TIMESTAMPDIFF(SECOND, created_at, NOW(3)) >= #{giveUpSeconds} THEN 2 ELSE 0 END,
                next_retry_at = TIMESTAMPADD(SECOND, #{backoffSeconds}, NOW(3)),
                retry_count = retry_count + 1
            WHERE id = #{id}
            """)
    int markRetry(
            @Param("id") long id,
            @Param("backoffSeconds") long backoffSeconds,
            @Param("giveUpSeconds") long giveUpSeconds);

    /** 消息本身非法（Broker 明确拒收，重试多少次结果都一样）：直接置为失败，不占着重试名额。 */
    @Update("UPDATE t_outbox SET status = 2, retry_count = retry_count + 1 WHERE id = #{id}")
    int markFailed(@Param("id") long id);

    @Select("SELECT MIN(created_at) FROM t_outbox WHERE status = 0")
    LocalDateTime selectOldestPendingCreatedAt();

    @Select("SELECT COUNT(*) FROM t_outbox WHERE status = 2")
    long countFailed();

    /** 已发送的记录保留 7 天，分批删除。 */
    @Delete("DELETE FROM t_outbox WHERE status = 1 AND updated_at < #{before} LIMIT #{limit}")
    int deleteSentBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
