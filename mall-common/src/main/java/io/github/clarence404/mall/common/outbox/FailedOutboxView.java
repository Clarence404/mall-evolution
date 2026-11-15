package io.github.clarence404.mall.common.outbox;

import java.time.LocalDateTime;

/**
 * 失败记录列表里的一项（接口响应）。只有定位问题需要的字段，不含消息体。
 *
 * @param id 记录 ID。雪花 ID 超过了 JavaScript 能精确表示的整数范围，所以用字符串返回；重投单条时原样放进路径
 * @param topic 消息的 Topic
 * @param msgKey 业务键（订单号 / 支付单号），拿它去查订单或日志
 * @param retryCount 置为失败前已经失败过几次：很小（通常是 1）多半是消息本身非法被立即置失败，很大则是重试满了时限
 * @param deliverAt 定时消息的投递时间，普通消息为空
 * @param createdAt 消息写入本地消息表的时间
 * @param failedAt 被置为失败的时间
 */
public record FailedOutboxView(
        String id,
        String topic,
        String msgKey,
        int retryCount,
        LocalDateTime deliverAt,
        LocalDateTime createdAt,
        LocalDateTime failedAt) {

    static FailedOutboxView of(FailedOutboxRow r) {
        return new FailedOutboxView(
                String.valueOf(r.id()),
                r.topic(),
                r.msgKey(),
                r.retryCount(),
                r.deliverAt(),
                r.createdAt(),
                r.failedAt());
    }
}
