package io.github.clarence404.mall.common.outbox;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.error.CommonErrorCode;
import io.github.clarence404.mall.common.web.CursorResult;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 失败记录的查看与重新投递（运营后台用，入口是 {@link OutboxAdminController}）。
 *
 * <p>背景：中继把「重试满 24 小时仍没发出去」或「消息本身非法」的记录置为失败（status = 2）后不再自动发送。
 * 以前只能手工改库（status 改回 0、created_at 改成当前时间）才会重投，现在由这里统一做，并且：
 *
 * <ul>
 *   <li>只动 status = 2 的记录——待发送、已发送的不受影响，重复调用也只有第一次生效
 *   <li>不改 created_at：放弃计时改用单独的 retry_since 列（重投时写成当前时间），见 {@link OutboxMapper#redrive}
 *   <li>重投只是把记录放回待发送，真正发送仍由中继（每秒扫表一次）完成，这里不直接碰 Broker，
 *       所以没启用 RocketMQ 的环境（测试）里也能调，只是没人来发
 *   <li>操作人、条件、条数写进日志，不记消息体
 * </ul>
 *
 * <p>因「消息本身非法」失败的记录重投后会再次立即失败，这是预期——中继每次发送都会重新校验，
 * 要先修数据或修代码。可以从列表里的 retryCount 看出来：这类记录通常一次就失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxAdminService {

    /** 列表每页最多多少条。 */
    static final int MAX_PAGE_SIZE = 50;

    /** 批量重投不传 limit 时的默认条数。 */
    public static final int DEFAULT_REDRIVE_LIMIT = 100;

    /**
     * 批量重投一次最多多少条：一条 UPDATE … IN (…) 改这么多行，锁持有时间很短；
     * 也避免几万条失败记录被一次放回去、瞬间压到刚恢复的 Broker 上。更多的分几次调。
     */
    public static final int MAX_REDRIVE_LIMIT = 500;

    /** 游标格式：Base64URL 编码的 {"id":"..."}，和订单列表的游标一个写法；客户端原样回传、不应解析。 */
    private static final Pattern CURSOR_FORMAT = Pattern.compile("^\\{\"id\":\"(\\d{1,19})\"}$");

    private final OutboxMapper outboxMapper;

    /**
     * 列出失败记录，新的在前。
     *
     * @param topic 只看某个 Topic；为空或空白表示全部
     * @param cursor 上一页返回的 nextCursor；第一页不传
     * @param size 每页条数，超出 1–{@value #MAX_PAGE_SIZE} 的按边界取
     */
    public CursorResult<FailedOutboxView> listFailed(String topic, String cursor, int size) {
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        // 多查一条用来判断还有没有下一页，不用再发一条 COUNT
        List<FailedOutboxRow> rows = outboxMapper.selectFailed(blankToNull(topic), decodeCursor(cursor), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<FailedOutboxRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore ? encodeCursor(page.getLast().id()) : null;
        return new CursorResult<>(page.stream().map(FailedOutboxView::of).toList(), next, hasMore);
    }

    /**
     * 重投单条。
     *
     * <ul>
     *   <li>记录不存在：404（OUTBOX_RECORD_NOT_FOUND）
     *   <li>记录存在但不是失败状态（待发送、已发送，或刚被别人重投过）：不做任何修改，redriven = 0。
     *       不报错是为了幂等——同一个请求重试一次、或两个人先后点同一条，结果都一样
     * </ul>
     *
     * @param operatorId 操作人的用户 ID（来自访问令牌），只用于记日志
     */
    public OutboxRedriveResult redriveOne(long id, long operatorId) {
        if (outboxMapper.selectStatus(id) == null) {
            throw new BizException(OutboxErrorCode.OUTBOX_RECORD_NOT_FOUND, "本地消息记录 " + id + " 不存在");
        }
        // 上面查到的状态只用来区分 404，不拿它做判断：是否重投由 UPDATE 的 status = 2 条件说了算，中间状态变了也不会改错
        int redriven = outboxMapper.redrive(List.of(id));
        log.info("outbox 重投单条：操作人={}，id={}，实际重投 {} 条", operatorId, id, redriven);
        return new OutboxRedriveResult(redriven, outboxMapper.countFailed());
    }

    /**
     * 批量重投：把最老的至多 limit 条失败记录放回待发送。
     *
     * <p>先查出这一批的 id、再按 id 更新，而不是一条 UPDATE … ORDER BY … LIMIT：后者是 MySQL 的方言，H2 不认，
     * 拆成两步两边都能跑。两步之间不需要事务——更新语句自己带 status = 2，这期间被别人重投走的行不会被重复计数。
     *
     * @param topic 只重投某个 Topic；为空或空白表示全部失败记录
     * @param limit 最多重投多少条，为空取 {@value #DEFAULT_REDRIVE_LIMIT}；范围由接口层校验（1–{@value #MAX_REDRIVE_LIMIT}），
     *     这里再夹一次，免得别处调用时绕过
     * @param operatorId 操作人的用户 ID，只用于记日志
     */
    public OutboxRedriveResult redriveBatch(String topic, Integer limit, long operatorId) {
        String topicFilter = blankToNull(topic);
        int max = Math.clamp(limit == null ? DEFAULT_REDRIVE_LIMIT : limit, 1, MAX_REDRIVE_LIMIT);
        List<Long> ids = outboxMapper.selectFailedIds(topicFilter, max);
        int redriven = ids.isEmpty() ? 0 : outboxMapper.redrive(ids); // 没有失败记录时不发空的 IN ()
        long remaining = outboxMapper.countFailedByTopic(topicFilter);
        log.info(
                "outbox 批量重投：操作人={}，topic={}，上限={}，实际重投 {} 条，剩余失败 {} 条",
                operatorId,
                topicFilter == null ? "(全部)" : topicFilter,
                max,
                redriven,
                remaining);
        return new OutboxRedriveResult(redriven, remaining);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String encodeCursor(long id) {
        String json = "{\"id\":\"" + id + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** 第一页（游标为空）返回 null，SQL 里就不拼 id 条件；格式不对统一报 400。 */
    static Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher m = CURSOR_FORMAT.matcher(json);
            if (m.matches()) {
                return Long.parseLong(m.group(1));
            }
        } catch (IllegalArgumentException ignored) {
            // 不是合法的 Base64，或数字超出 long 范围：落到下面统一报错
        }
        throw new BizException(CommonErrorCode.VALIDATION_FAILED, "游标非法");
    }
}
