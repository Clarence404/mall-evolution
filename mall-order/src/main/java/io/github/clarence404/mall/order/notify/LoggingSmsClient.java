package io.github.clarence404.mall.order.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class LoggingSmsClient implements SmsClient {

    @Override
    public void send(String phone, String content) {
        String masked =
                phone.length() >= 7 ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : phone;
        log.info("[sms] to={} content={}", masked, content);
    }
}
