package io.github.clarence404.mall.common.outbox;

import io.github.clarence404.mall.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 本地消息表后台接口的错误码，前缀 OUTBOX_。 */
public enum OutboxErrorCode implements ErrorCode {
    /** 按 id 重投时这条记录不存在：多半是 id 抄错了，或者调到了别的服务（三个服务各有一张 t_outbox）。 */
    OUTBOX_RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "本地消息记录不存在");

    private final HttpStatus status;
    private final String title;

    OutboxErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String code() {
        return name();
    }
}
