package io.github.clarence404.mall.common.outbox;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
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
 * <p>发送成功但 markSent 前宕机、或租约到期还没发完，记录会再发一次，由消费端幂等兜住。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
public class OutboxRelay {

    /** 认领后的租约：一批最多 100 条，每条发送超时 1 秒，2 分钟足够发完。 */
    static final int LEASE_SECONDS = 120;

    private final OutboxMapper outboxMapper;
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelay(
            OutboxMapper outboxMapper,
            ClientServiceProvider provider,
            Producer producer,
            TransactionTemplate transactionTemplate) {
        this.outboxMapper = outboxMapper;
        this.provider = provider;
        this.producer = producer;
        this.transactionTemplate = readCommitted(transactionTemplate);
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

    /** 在事务外发送一条并回写状态；发送失败返回 false。 */
    boolean send(OutboxRecord r) {
        MessageBuilder b = provider.newMessageBuilder()
                .setTopic(r.topic())
                .setKeys(r.msgKey()) // 控制台按订单号查消息，消费端按它去重
                .setBody(r.payload().getBytes(StandardCharsets.UTF_8));
        if (r.deliverAt() != null) { // 超时关单：交给 RocketMQ 定时消息
            b.setDeliveryTimestamp(
                    r.deliverAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        // 在原 Trace 下发送：agent 生成的 producer span 挂回下单请求，traceparent 随消息属性传给消费者
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), r, OUTBOX_GETTER);
        try (Scope ignored = parent.makeCurrent()) {
            producer.send(b.build()); // 同步发送，Broker 确认后才置 SENT
        } catch (ClientException e) {
            log.warn("消息发送失败，等待重试，topic={}, key={}", r.topic(), r.msgKey(), e);
            outboxMapper.markRetry(r.id()); // retry_count + 1，按退避推迟 next_retry_at，第 10 次失败置为失败
            return false;
        }
        outboxMapper.markSent(r.id());
        return true;
    }
}
