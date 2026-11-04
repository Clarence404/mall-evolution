package io.github.clarence404.mall.order.notify;

import io.github.clarence404.mall.common.mq.ConsumeRecordMapper;
import io.github.clarence404.mall.order.api.OrderPaidEvent;
import io.github.clarence404.mall.user.api.UserApi;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * notify-consumer：支付成功短信与 App 推送。外部调用没法和去重记录放进一个事务，所以「先发后记」：
 * 先查记录，没有才发，发成功再插记录；发完、插记录前宕机会多发一条，可以接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaidNotifier {

    static final String GROUP = "notify-consumer";
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private final ConsumeRecordMapper consumeRecordMapper;
    private final UserApi userApi;
    private final SmsClient smsClient;
    private final PushClient pushClient;

    public void onPaid(OrderPaidEvent e) {
        if (consumeRecordMapper.exists(GROUP, e.orderNo())) {
            return; // 重复投递
        }
        if (e.paidAt() != null && e.paidAt().isBefore(LocalDateTime.now().minus(STALE_AFTER))) {
            // 迟到的「支付成功」短信只会让用户困惑：直接确认不发
            log.info("支付已超过 30 分钟，不再通知，orderNo={}", e.orderNo());
            consumeRecordMapper.insertIgnore(GROUP, e.orderNo());
            return;
        }
        userApi.findById(e.userId()).ifPresent(u -> {
            smsClient.send(u.phone(), "您的订单 " + e.orderNo() + " 已支付成功，我们会尽快为您发货。");
            pushClient.push(u.id(), "支付成功", "订单 " + e.orderNo() + " 已支付成功");
        });
        consumeRecordMapper.insertIgnore(GROUP, e.orderNo());
    }
}
