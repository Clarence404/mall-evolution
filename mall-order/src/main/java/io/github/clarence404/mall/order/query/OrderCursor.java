package io.github.clarence404.mall.order.query;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.error.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 游标：Base64URL 编码的 {@code {"id":"..."}}，客户端原样回传、不应解析；以后改排序字段只改编解码，接口不变。
 */
final class OrderCursor {

    private static final Pattern FORMAT = Pattern.compile("^\\{\"id\":\"(\\d{1,19})\"}$");

    private OrderCursor() {}

    static String encode(long id) {
        String json = "{\"id\":\"" + id + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static Long decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null; // 首页不拼 id 条件
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher m = FORMAT.matcher(json);
            if (m.matches()) {
                return Long.parseLong(m.group(1));
            }
        } catch (IllegalArgumentException ignored) {
            // 落到下面统一报错
        }
        throw new BizException(CommonErrorCode.VALIDATION_FAILED, "游标非法");
    }
}
