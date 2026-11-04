package io.github.clarence404.mall.order.notify;

/** 短信服务商的客户端。演示工程用 {@link LoggingSmsClient}，生产换成服务商 SDK。 */
public interface SmsClient {

    void send(String phone, String content);
}
