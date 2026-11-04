package io.github.clarence404.mall.common.json;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 消息体与 t_outbox.payload 的 JSON 编解码。Jackson 3 的异常都是非受检的。 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // 生产者加字段不影响老消费者
            .build();

    private Json() {}

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    /** RocketMQ 5 客户端的 {@code MessageView.getBody()} 返回只读的 ByteBuffer。 */
    public static <T> T read(ByteBuffer body, Class<T> type) {
        ByteBuffer copy = body.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), type);
    }
}
