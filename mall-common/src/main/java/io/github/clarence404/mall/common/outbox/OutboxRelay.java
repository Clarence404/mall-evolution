package io.github.clarence404.mall.common.outbox;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.java.exception.BadRequestException;
import org.apache.rocketmq.client.java.exception.PayloadEmptyException;
import org.apache.rocketmq.client.java.exception.PayloadTooLargeException;
import org.apache.rocketmq.client.java.exception.RequestHeaderFieldsTooLargeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 中继投递：{@link #relayOne} 供 afterCommit 立即发送，{@link #relayBatch} 由 {@link OutboxRelayJob} 每秒调用、扫表兜底。
 *
 * <p>分三步，发送不在事务里：
 *
 * <ol>
 *   <li>短事务认领：FOR UPDATE SKIP LOCKED 选出到期记录，把 next_retry_at 推后 {@value #LEASE_SECONDS} 秒作为租约，立即提交。
 *       提交后行锁和连接都已释放，别的实例扫表时这批记录还没到期，不会重复认领。认领事务用 READ COMMITTED：
 *       可重复读下按 idx_status_retry 扫描会加间隙锁，租约更新又要改这条索引，多个实例并发认领时会互相死锁
 *       （集成测试里能稳定复现）；读已提交只锁命中的行。偶发的锁冲突当成「本轮没认领到」，下一轮再来
 *   <li>事务外同步发送：Broker 故障时每条最多卡 requestTimeout（1 秒），卡住的只是中继线程，不占数据库连接
 *   <li>单条语句回写：成功 markSent；失败 markRetry 按退避推迟，本批剩下的记录放回去等下一轮，不再逐条撞超时
 * </ol>
 *
 * <p>失败分两类：
 *
 * <ul>
 *   <li>Broker 或网络的问题（超时、5xx、限流、Topic 还没建好……）：一直重试，退避 1、2、4…秒翻倍，封顶 4 分钟一次；
 *       记录创建满 {@code mall.outbox.give-up-after}（默认 24 小时）还没发出去才置为失败
 *   <li>消息本身非法（Broker 明确拒收：请求不合法、消息体过大等）：重试多少次结果都一样，立即置为失败，本批其余记录照常发送
 * </ul>
 *
 * <p>置为失败的记录不会再自动发送：{@code outbox.failed.count} 大于 0 触发 OutboxFailedRecords 告警，排查后由人工把
 * status 改回 0（同时把 created_at 改成当前时间，重新获得 24 小时的重试窗口）。
 *
 * <p>发送成功但 markSent 前宕机、或租约到期还没发完，记录会再发一次，由消费端幂等兜住。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
public class OutboxRelay {

    /** 认领后的租约：一批最多 100 条，每条发送超时 1 秒，2 分钟足够发完。 */
    static final int LEASE_SECONDS = 120;

    /** 退避封顶：最久 4 分钟重试一次。 */
    static final long MAX_BACKOFF_SECONDS = 240;

    /** 默认的放弃时限：创建满 24 小时还没发出去才置为失败。 */
    static final Duration DEFAULT_GIVE_UP_AFTER = Duration.ofHours(24);

    private final OutboxMapper outboxMapper;
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final TransactionTemplate transactionTemplate;
    private final long giveUpSeconds;

    @Autowired
    public OutboxRelay(
            OutboxMapper outboxMapper,
            ClientServiceProvider provider,
            Producer producer,
            TransactionTemplate transactionTemplate,
            @Value("${mall.outbox.give-up-after:24h}") Duration giveUpAfter) {
        this.outboxMapper = outboxMapper;
        this.provider = provider;
        this.producer = producer;
        this.transactionTemplate = readCommitted(transactionTemplate);
        this.giveUpSeconds = giveUpAfter.toSeconds();
    }

    /** 测试里手工创建中继时用：放弃时限取默认的 24 小时。 */
    public OutboxRelay(
            OutboxMapper outboxMapper,
            ClientServiceProvider provider,
            Producer producer,
            TransactionTemplate transactionTemplate) {
        this(outboxMapper, provider, producer, transactionTemplate, DEFAULT_GIVE_UP_AFTER);
    }

    /** 第 n 次失败（n 从 0 数）后等多久再试：1、2、4、8…秒翻倍，到 240 秒（4 分钟）封顶，之后一直是 4 分钟一次。 */
    static long backoffSeconds(int retryCount) {
        return Math.min(1L << Math.min(Math.max(retryCount, 0), 8), MAX_BACKOFF_SECONDS);
    }

    /** 认领用的短事务：读已提交、5 秒超时。没有事务管理器（单元测试的替身）时原样使用。 */
    static TransactionTemplate readCommitted(TransactionTemplate template) {
        if (template.getTransactionManager() == null) {
            return template;
        }
        TransactionTemplate claim = new TransactionTemplate(template.getTransactionManager());
        claim.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        claim.setTimeout(5);
        return claim;
    }

    public void relayOne(long id) {
        OutboxRecord r;
        try {
            r = transactionTemplate.execute(status -> {
                OutboxRecord pending = outboxMapper.selectPendingForUpdate(id);
                if (pending != null) {
                    outboxMapper.lease(List.of(id), LEASE_SECONDS);
                }
                return pending;
            });
        } catch (TransientDataAccessException e) {
            log.debug("认领 outbox 记录时锁冲突，交给扫表兜底，id={}", id, e);
            return;
        }
        if (r != null) {
            send(r);
        }
    }

    /** 返回本轮实际发出的条数；小于 limit 说明扫完了或 Broker 出了问题。 */
    public int relayBatch(int limit) {
        List<OutboxRecord> due;
        try {
            due = transactionTemplate.execute(status -> {
                List<OutboxRecord> claimed = outboxMapper.selectDueForUpdate(limit);
                if (!claimed.isEmpty()) {
                    outboxMapper.lease(claimed.stream().map(OutboxRecord::id).toList(), LEASE_SECONDS);
                }
                return claimed;
            });
        } catch (TransientDataAccessException e) { // 死锁或锁等待超时：事务已回滚、租约没生效，下一轮再认领
            log.debug("认领 outbox 记录时锁冲突，本轮跳过", e);
            return 0;
        }
        if (due == null) {
            return 0;
        }
        for (int i = 0; i < due.size(); i++) {
            if (!send(due.get(i))) {
                List<Long> rest = due.subList(i + 1, due.size()).stream()
                        .map(OutboxRecord::id)
                        .toList();
                if (!rest.isEmpty()) {
                    outboxMapper.releaseLease(rest); // 放回去，下一轮再试
                }
                return i;
            }
        }
        return due.size();
    }

    /** 只返回 r.traceParent() 的 TextMapGetter。 */
    static final TextMapGetter<OutboxRecord> OUTBOX_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(OutboxRecord carrier) {
            return List.of("traceparent");
        }

        @Override
        public String get(OutboxRecord carrier, String key) {
            return carrier == null || !"traceparent".equals(key) ? null : carrier.traceParent();
        }
    };

    /**
     * 在事务外发送一条并回写状态。返回 false 表示 Broker 或网络有问题、本批不必再往下试；
     * 发送成功、或这条消息本身非法（已置为失败）都返回 true，本批继续。
     */
    boolean send(OutboxRecord r) {
        Message message;
        try {
            MessageBuilder b = provider.newMessageBuilder()
                    .setTopic(r.topic())
                    .setKeys(r.msgKey()) // 控制台按订单号查消息，消费端按它去重
                    .setBody(r.payload().getBytes(StandardCharsets.UTF_8));
            if (r.deliverAt() != null) { // 超时关单：交给 RocketMQ 定时消息
                b.setDeliveryTimestamp(
                        r.deliverAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
            message = b.build();
        } catch (IllegalArgumentException | NullPointerException e) { // 客户端校验没过：Topic、Keys 不合法等
            log.error("消息不合法，置为失败不再重试，topic={}, key={}", r.topic(), r.msgKey(), e);
            outboxMapper.markFailed(r.id());
            return true;
        }
        // 在原 Trace 下发送：agent 生成的 producer span 挂回下单请求，traceparent 随消息属性传给消费者
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), r, OUTBOX_GETTER);
        try (Scope ignored = parent.makeCurrent()) {
            producer.send(message); // 同步发送，Broker 确认后才置 SENT
        } catch (ClientException e) {
            if (isIllegalMessage(e)) {
                log.error("Broker 拒收这条消息，置为失败不再重试，topic={}, key={}", r.topic(), r.msgKey(), e);
                outboxMapper.markFailed(r.id());
                return true;
            }
            log.warn("消息发送失败，等待重试，topic={}, key={}，已重试 {} 次", r.topic(), r.msgKey(), r.retryCount(), e);
            // retry_count + 1，按退避推迟 next_retry_at；创建超过放弃时限（默认 24 小时）才置为失败
            outboxMapper.markRetry(r.id(), backoffSeconds(r.retryCount()), giveUpSeconds);
            return false;
        }
        outboxMapper.markSent(r.id());
        return true;
    }

    /**
     * 是不是「消息本身非法」：Broker 返回 400 类（请求不合法、消息体为空）或消息体、消息头过大。这几种换个时间再发结果一样。
     * 其余的（超时、5xx、限流 429、Topic 不存在 404、鉴权失败……）都是环境问题，修好后重试能成功，不在这里。
     */
    static boolean isIllegalMessage(ClientException e) {
        return e instanceof BadRequestException
                || e instanceof PayloadEmptyException
                || e instanceof PayloadTooLargeException
                || e instanceof RequestHeaderFieldsTooLargeException;
    }
}
