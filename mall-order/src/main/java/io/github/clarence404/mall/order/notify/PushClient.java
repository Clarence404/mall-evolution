package io.github.clarence404.mall.order.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** App 推送平台的客户端。演示工程只打日志，生产换成推送平台 SDK。 */
public interface PushClient {

    void push(long userId, String title, String content);

    @Slf4j
    @Component
    class LoggingPushClient implements PushClient {

        @Override
        public void push(long userId, String title, String content) {
            log.info("[push] user={} title={} content={}", userId, title, content);
        }
    }
}
