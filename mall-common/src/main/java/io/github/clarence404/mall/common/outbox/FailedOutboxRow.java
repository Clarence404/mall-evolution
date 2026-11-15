package io.github.clarence404.mall.common.outbox;

import java.time.LocalDateTime;

/**
 * 失败记录列表查询的一行（{@link OutboxMapper#selectFailed}）。
 *
 * <p>和 {@link OutboxRecord} 分开：这里没有 payload 字段，SQL 也不查它——列表是给人看的，
 * 消息体可能带业务数据，不该出现在后台接口的响应里。MyBatis 直接读写字段，访问方法沿用 record 风格。
 */
public class FailedOutboxRow {

    private long id;
    private String topic;
    private String msgKey;
    private int retryCount;
    private LocalDateTime deliverAt;
    private LocalDateTime createdAt;
    /** 被置为失败的时间（即这行最后一次更新的 updated_at）。 */
    private LocalDateTime failedAt;

    public long id() {
        return id;
    }

    public String topic() {
        return topic;
    }

    public String msgKey() {
        return msgKey;
    }

    public int retryCount() {
        return retryCount;
    }

    public LocalDateTime deliverAt() {
        return deliverAt;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime failedAt() {
        return failedAt;
    }
}
