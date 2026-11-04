package io.github.clarence404.mall.common.outbox;

import io.github.clarence404.mall.common.id.Ids;
import java.time.LocalDateTime;

/** t_outbox 的一行。访问方法沿用 record 风格（{@code r.topic()}），MyBatis 直接读写字段。 */
public class OutboxRecord {

    public static final int PENDING = 0;
    public static final int SENT = 1;
    public static final int FAILED = 2;

    private long id;
    private String topic;
    private String msgKey;
    private String payload;
    private LocalDateTime deliverAt;
    private int status;
    private int retryCount;
    private LocalDateTime nextRetryAt;

    public static OutboxRecord of(String topic, String msgKey, String payload, LocalDateTime deliverAt) {
        OutboxRecord r = new OutboxRecord();
        r.id = Ids.next();
        r.topic = topic;
        r.msgKey = msgKey;
        r.payload = payload;
        r.deliverAt = deliverAt;
        r.status = PENDING;
        r.nextRetryAt = LocalDateTime.now().plusSeconds(3); // 提交后立即由 afterCommit 发送，扫表 3 秒后才兜底
        return r;
    }

    public long id() {
        return id;
    }

    public String topic() {
        return topic;
    }

    public String msgKey() {
        return msgKey;
    }

    public String payload() {
        return payload;
    }

    public LocalDateTime deliverAt() {
        return deliverAt;
    }

    public int status() {
        return status;
    }

    public int retryCount() {
        return retryCount;
    }

    public LocalDateTime nextRetryAt() {
        return nextRetryAt;
    }
}
