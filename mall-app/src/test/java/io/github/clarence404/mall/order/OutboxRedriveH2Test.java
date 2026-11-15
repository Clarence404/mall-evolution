package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.common.outbox.OutboxMapper;
import io.github.clarence404.mall.common.outbox.OutboxRelay;
import io.github.clarence404.mall.support.H2TestBase;
import io.github.clarence404.mall.support.TestTokens;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 本地消息表失败记录的重投接口 /api/admin/outbox/failed/**（H2 MySQL 模式跑真实 SQL，不需要 Docker）。
 *
 * <p>覆盖：重投后被中继重新发出、非失败记录不受影响、普通用户 403 与未登录 401、批量上限、
 * 「满 24 小时放弃」从重投时刻重新算（而且不改 created_at）、列表不返回消息体。
 */
class OutboxRedriveH2Test extends H2TestBase {

    static final int ONE_DAY = 24 * 3600;

    /** 运营账号（ADMIN 角色）的访问令牌；10009 是演示数据里的运营用户。 */
    static final String ADMIN = TestTokens.bearer(10009L, "ADMIN");

    static final String USER = TestTokens.bearer(10001L);

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxMapper outboxMapper;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_outbox");
    }

    /**
     * 插一条指定状态的记录，创建时间在 hoursAgo 小时前；返回它的 id。
     * 失败记录的 next_retry_at 也放在很久以前，贴近真实情况（置失败后这个字段不再更新）。
     */
    long insert(String topic, String key, int status, int retryCount, int hoursAgo) {
        long id = Ids.next();
        jdbcTemplate.update(
                "INSERT INTO t_outbox (id, topic, msg_key, payload, status, retry_count, next_retry_at, created_at)"
                        + " VALUES (?, ?, ?, '{\"secret\":\"do-not-leak\"}', ?, ?,"
                        + " DATEADD('HOUR', ?, CURRENT_TIMESTAMP(3)), DATEADD('HOUR', ?, CURRENT_TIMESTAMP(3)))",
                id,
                topic,
                key,
                status,
                retryCount,
                -hoursAgo,
                -hoursAgo);
        return id;
    }

    int statusOf(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM t_outbox WHERE id = ?", Integer.class, id);
    }

    // ---------- 重投单条 ----------

    /** 失败记录重投后回到待发送，中继下一轮扫表就把它发出去并置为已发送。 */
    @Test
    void redrivenRecordIsPickedUpAndSentByTheRelay() throws Exception {
        long id = insert("order-paid", "K1", 2, 37, 30);

        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(1))
                .andExpect(jsonPath("$.remaining").value(0));

        // 状态回到待发送、重试次数清零（退避从 1 秒重新开始）、立即可被认领
        assertThat(statusOf(id)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT retry_count FROM t_outbox WHERE id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT next_retry_at <= CURRENT_TIMESTAMP(3) FROM t_outbox WHERE id = ?", Boolean.class, id))
                .isTrue();

        // 用替身 Producer 手工建一个中继（测试环境没启用 RocketMQ，容器里没有中继 Bean），扫一轮
        Producer producer = mock(Producer.class);
        OutboxRelay relay =
                new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), producer, transactionTemplate);
        assertThat(relay.relayBatch(100)).isEqualTo(1);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(producer, times(1)).send(sent.capture());
        assertThat(sent.getValue().getTopic()).isEqualTo("order-paid");
        assertThat(sent.getValue().getKeys()).containsExactly("K1");
        assertThat(statusOf(id)).isEqualTo(1);
    }

    /** 幂等：同一条再重投一次，什么都不改，返回 redriven = 0 而不是报错。 */
    @Test
    void redrivingTheSameRecordTwiceOnlyTakesEffectOnce() throws Exception {
        long id = insert("order-paid", "K1", 2, 5, 30);

        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(jsonPath("$.redriven").value(1));
        String retrySince = jdbcTemplate.queryForObject(
                "SELECT CAST(retry_since AS VARCHAR) FROM t_outbox WHERE id = ?", String.class, id);

        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(0));
        // 第二次没有再刷新 retry_since：不能靠反复点重投无限延长重试窗口
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CAST(retry_since AS VARCHAR) FROM t_outbox WHERE id = ?", String.class, id))
                .isEqualTo(retrySince);
    }

    /** 只允许动失败记录：待发送、已发送的记录按 id 重投、批量重投都不会被改。 */
    @Test
    void pendingAndSentRecordsAreNeverTouched() throws Exception {
        long pending = insert("order-paid", "PENDING", 0, 3, 1);
        long sent = insert("order-paid", "SENT", 1, 0, 1);

        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", pending).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(0));
        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", sent).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(0));
        mvc.perform(post("/api/admin/outbox/failed/redrive").header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(0));

        assertThat(statusOf(pending)).isZero();
        assertThat(statusOf(sent)).isEqualTo(1); // 已发送的没有被放回去重发
        assertThat(jdbcTemplate.queryForObject("SELECT retry_count FROM t_outbox WHERE id = ?", Integer.class, pending))
                .isEqualTo(3); // 待发送记录的重试计数没有被清零
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM t_outbox WHERE retry_since IS NOT NULL", Integer.class))
                .isZero();
    }

    @Test
    void unknownIdReturns404() throws Exception {
        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", 1L).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OUTBOX_RECORD_NOT_FOUND"));
    }

    // ---------- 放弃计时 ----------

    /**
     * 放弃计时从重投时刻重新算：一条 30 小时前创建的记录重投后再遇到 Broker 故障，不会因为「创建已超过 24 小时」
     * 立刻又被置失败；要等到距重投满 24 小时才放弃。created_at 始终没被改动。
     */
    @Test
    void giveUpClockRestartsFromTheRedriveNotFromCreation() throws Exception {
        long id = insert("order-paid", "K1", 2, 40, 30);
        String createdAt = jdbcTemplate.queryForObject(
                "SELECT CAST(created_at AS VARCHAR) FROM t_outbox WHERE id = ?", String.class, id);

        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(jsonPath("$.redriven").value(1));

        // 重投后又发送失败：仍是待发送，继续按退避重试
        outboxMapper.markRetry(id, 1, ONE_DAY);
        assertThat(statusOf(id)).isZero();
        // created_at 还是 30 小时前，没有被拿来当计时起点，也没有被改
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT CAST(created_at AS VARCHAR) FROM t_outbox WHERE id = ?", String.class, id))
                .isEqualTo(createdAt);

        // 距重投已过 25 小时还没发出去：这时才置为失败
        jdbcTemplate.update(
                "UPDATE t_outbox SET retry_since = DATEADD('HOUR', -25, CURRENT_TIMESTAMP(3)) WHERE id = ?", id);
        outboxMapper.markRetry(id, 1, ONE_DAY);
        assertThat(statusOf(id)).isEqualTo(2);
    }

    /** 「最老待发送时长」指标也从重投时刻算：刚重投的老记录不会让 OutboxPendingTooLong 误报。 */
    @Test
    void pendingAgeIsMeasuredFromTheRedrive() throws Exception {
        long id = insert("order-paid", "K1", 2, 40, 30);

        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", id).header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(jsonPath("$.redriven").value(1));

        assertThat(outboxMapper.selectOldestPendingCreatedAt())
                .isAfter(java.time.LocalDateTime.now().minusMinutes(5));
    }

    // ---------- 批量重投 ----------

    /** 批量上限：limit = 2 时只重投最老的 2 条，remaining 告诉调用方还剩 1 条；再调一次处理完。 */
    @Test
    void batchRedriveHonoursTheLimitAndReportsWhatIsLeft() throws Exception {
        long first = insert("order-paid", "K1", 2, 1, 30);
        long second = insert("order-paid", "K2", 2, 1, 30);
        long third = insert("order-paid", "K3", 2, 1, 30);

        mvc.perform(post("/api/admin/outbox/failed/redrive")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(2))
                .andExpect(jsonPath("$.remaining").value(1));
        assertThat(statusOf(first)).isZero();
        assertThat(statusOf(second)).isZero();
        assertThat(statusOf(third)).isEqualTo(2);

        // 不带请求体：全部失败记录、默认上限 100
        mvc.perform(post("/api/admin/outbox/failed/redrive").header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(1))
                .andExpect(jsonPath("$.remaining").value(0));
        assertThat(statusOf(third)).isZero();
    }

    /** 单次上限 500：超过直接 400，不悄悄截断；0 和负数同样 400。一条记录都不会被改。 */
    @Test
    void batchLimitAboveTheMaximumIsRejected() throws Exception {
        long id = insert("order-paid", "K1", 2, 1, 30);

        for (String body : new String[] {"{\"limit\":501}", "{\"limit\":0}", "{\"limit\":-1}"}) {
            mvc.perform(post("/api/admin/outbox/failed/redrive")
                            .header(HttpHeaders.AUTHORIZATION, ADMIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(statusOf(id)).isEqualTo(2);

        mvc.perform(post("/api/admin/outbox/failed/redrive")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(1));
    }

    /** 按 topic 过滤：只重投指定 Topic 的失败记录，remaining 也只数这个 Topic。 */
    @Test
    void batchRedriveCanBeLimitedToOneTopic() throws Exception {
        long paid = insert("order-paid", "K1", 2, 1, 30);
        long timeout = insert("order-timeout", "K1", 2, 1, 30);

        mvc.perform(post("/api/admin/outbox/failed/redrive")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"order-paid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redriven").value(1))
                .andExpect(jsonPath("$.remaining").value(0));

        assertThat(statusOf(paid)).isZero();
        assertThat(statusOf(timeout)).isEqualTo(2);
    }

    // ---------- 列表 ----------

    /** 列表只有失败记录、新的在前、游标翻页；响应里没有消息体。 */
    @Test
    void listReturnsOnlyFailedRecordsWithoutPayloadAndPaginates() throws Exception {
        long oldest = insert("order-paid", "K1", 2, 1, 30);
        long middle = insert("order-timeout", "K2", 2, 40, 30);
        long newest = insert("order-paid", "K3", 2, 1, 30);
        insert("order-paid", "PENDING", 0, 0, 1);

        String page1 = mvc.perform(
                        get("/api/admin/outbox/failed").param("size", "2").header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(String.valueOf(newest))) // 字符串：雪花 ID 超出 JS 的安全整数
                .andExpect(jsonPath("$.items[0].topic").value("order-paid"))
                .andExpect(jsonPath("$.items[0].msgKey").value("K3"))
                .andExpect(jsonPath("$.items[0].retryCount").value(1))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].failedAt").exists())
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.items[1].id").value(String.valueOf(middle)))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(page1).doesNotContain("do-not-leak"); // 消息体的内容没有以任何形式出现在响应里

        String cursor = JsonPath.read(page1, "$.nextCursor");
        mvc.perform(get("/api/admin/outbox/failed")
                        .param("size", "2")
                        .param("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(String.valueOf(oldest)))
                .andExpect(jsonPath("$.hasMore").value(false));

        mvc.perform(get("/api/admin/outbox/failed")
                        .param("topic", "order-timeout")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].msgKey").value("K2"));

        mvc.perform(get("/api/admin/outbox/failed")
                        .param("cursor", "not-a-cursor")
                        .header(HttpHeaders.AUTHORIZATION, ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 鉴权 ----------

    /** 普通用户调三个接口都是 403，失败记录原样不动。 */
    @Test
    void normalUserGets403() throws Exception {
        long id = insert("order-paid", "K1", 2, 1, 30);

        mvc.perform(get("/api/admin/outbox/failed").header(HttpHeaders.AUTHORIZATION, USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", id).header(HttpHeaders.AUTHORIZATION, USER))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/outbox/failed/redrive").header(HttpHeaders.AUTHORIZATION, USER))
                .andExpect(status().isForbidden());

        assertThat(statusOf(id)).isEqualTo(2);
    }

    /** 未登录调三个接口都是 401，失败记录原样不动。 */
    @Test
    void anonymousGets401() throws Exception {
        long id = insert("order-paid", "K1", 2, 1, 30);

        mvc.perform(get("/api/admin/outbox/failed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/admin/outbox/failed/{id}/redrive", id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/outbox/failed/redrive")).andExpect(status().isUnauthorized());

        assertThat(statusOf(id)).isEqualTo(2);
    }

    /** 兜底确认：中继没被 any() 之类的宽松匹配骗过——没有失败记录被重投时，中继一条都不发。 */
    @Test
    void nothingIsSentWhenNothingWasRedriven() throws Exception {
        insert("order-paid", "K1", 2, 1, 30);
        Producer producer = mock(Producer.class);
        OutboxRelay relay =
                new OutboxRelay(outboxMapper, ClientServiceProvider.loadService(), producer, transactionTemplate);

        assertThat(relay.relayBatch(100)).isZero();

        verify(producer, times(0)).send(any(Message.class));
    }
}
