package io.github.clarence404.mall.order.notify;

import io.github.clarence404.mall.order.pay.PayNotify;
import io.github.clarence404.mall.user.api.UserApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 支付成功短信。阶段一在回调事务提交后同步调用。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaidNotifier {

    private final UserApi userApi;
    private final SmsClient smsClient;

    public void notifyPaid(PayNotify n) {
        userApi.findById(n.userId())
                .ifPresent(u -> smsClient.send(u.phone(), "您的订单 " + n.orderNo() + " 已支付成功，我们会尽快为您发货。"));
    }
}
