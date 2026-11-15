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

    /**
     * 认领：在加锁查询的同一个短事务里把 next_retry_at 推后作为租约，提交后别的实例扫表也看不到这批记录。
     * 用 TIMESTAMPADD 而不是 DATE_ADD … INTERVAL（理由同 {@link #markRetry}）：扫表认领这条路径在 H2 测试里也能跑到。
     */
    @Update("""
            <script>
            UPDATE t_outbox SET next_retry_at = TIMESTAMPADD(SECOND, #{seconds}, NOW(3))
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
     * 交给 OutboxFailedRecords 告警，排查后经重投接口放回待发送。原先「第 10 次失败就置失败」约 9 分钟就放弃，Broker 抖动十来分钟就会留下一批死记录。
     *
     * <p>计时起点是 COALESCE(retry_since, created_at)：没被重投过的记录 retry_since 为空，从创建时间起算；
     * 运营调重投接口后 retry_since 是重投时刻，从那时起重新获得完整的重试窗口（见 {@link #redrive}）。
     *
     * <p>用 TIMESTAMPADD / TIMESTAMPDIFF 而不是 DATE_ADD … INTERVAL：两者 MySQL 都支持，前者 H2 的 MySQL 模式也认，
     * 这条 SQL 才能在不需要 Docker 的测试里跑到。
     */
    @Update("""
            UPDATE t_outbox SET
                status = CASE WHEN TIMESTAMPDIFF(SECOND, COALESCE(retry_since, created_at), NOW(3)) >= #{giveUpSeconds} THEN 2 ELSE 0 END,
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

    /**
     * 最老一条待发送记录从什么时候开始等（outbox.pending.age 指标用）。
     *
     * <p>重投过的记录按重投时刻算，而不是创建时间：否则一条几天前创建的失败记录刚被重投，
     * 「最老待发送时长」立刻变成几天，OutboxPendingTooLong 会误报。
     */
    @Select("SELECT MIN(COALESCE(retry_since, created_at)) FROM t_outbox WHERE status = 0")
    LocalDateTime selectOldestPendingCreatedAt();

    @Select("SELECT COUNT(*) FROM t_outbox WHERE status = 2")
    long countFailed();

    // ---------- 以下几条给运营后台的「失败记录重投」用（OutboxAdminService），只碰 status = 2 的记录 ----------

    /**
     * 列出失败记录：按 id 倒序（雪花 ID 随时间递增，即新的在前），游标分页。
     *
     * <p>故意不查 payload：消息体里可能有手机号、地址、金额等业务数据，排查用 topic + msg_key（订单号 / 支付单号）就够了。
     * updated_at 取别名 failed_at——失败记录最后一次更新就是被置为失败的那一刻。
     *
     * @param topic 只看某个 Topic，为空表示全部
     * @param beforeId 游标：只返回 id 小于它的记录，为空表示第一页
     */
    @Select("""
            <script>
            SELECT id, topic, msg_key, retry_count, deliver_at, created_at, updated_at AS failed_at
            FROM t_outbox WHERE status = 2
            <if test="topic != null">AND topic = #{topic}</if>
            <if test="beforeId != null">AND id &lt; #{beforeId}</if>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    List<FailedOutboxRow> selectFailed(
            @Param("topic") String topic, @Param("beforeId") Long beforeId, @Param("limit") int limit);

    /** 批量重投前先挑出要动的那批：最老的失败记录优先，最多 limit 条。 */
    @Select("""
            <script>
            SELECT id FROM t_outbox WHERE status = 2
            <if test="topic != null">AND topic = #{topic}</if>
            ORDER BY id LIMIT #{limit}
            </script>
            """)
    List<Long> selectFailedIds(@Param("topic") String topic, @Param("limit") int limit);

    /** 按条件数失败记录（批量重投后告诉调用方还剩多少）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM t_outbox WHERE status = 2
            <if test="topic != null">AND topic = #{topic}</if>
            </script>
            """)
    long countFailedByTopic(@Param("topic") String topic);

    /** 记录当前的状态；不存在返回 null（单条重投用它区分「不存在」和「存在但不是失败状态」）。 */
    @Select("SELECT status FROM t_outbox WHERE id = #{id}")
    Integer selectStatus(@Param("id") long id);

    /**
     * 重投：把失败记录放回待发送，中继下一轮扫表（每秒一次）就会发出去。
     *
     * <ul>
     *   <li>status 置回 0、retry_count 清零（退避从 1 秒重新开始）、next_retry_at 置为当前时间（立即可被认领）
     *   <li>retry_since 置为当前时间：「满 24 小时放弃」从重投时刻重新算；created_at 不动，仍是消息写入的时间
     *   <li>WHERE 里带 status = 2：待发送、已发送的记录即使 id 被传进来也不会被改，重复调用也只有第一次生效（幂等）；
     *       两个人同时点重投，同一行只会被其中一条语句改到，返回值加起来正好是实际重投的条数
     * </ul>
     *
     * @return 实际被放回待发送的条数
     */
    @Update("""
            <script>
            UPDATE t_outbox SET status = 0, retry_count = 0, next_retry_at = NOW(3), retry_since = NOW(3)
            WHERE status = 2 AND id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int redrive(@Param("ids") List<Long> ids);

    /** 已发送的记录保留 7 天，分批删除。 */
    @Delete("DELETE FROM t_outbox WHERE status = 1 AND updated_at < #{before} LIMIT #{limit}")
    int deleteSentBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
