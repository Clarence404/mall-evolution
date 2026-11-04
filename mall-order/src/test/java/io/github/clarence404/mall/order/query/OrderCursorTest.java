package io.github.clarence404.mall.order.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.mall.common.error.BizException;
import org.junit.jupiter.api.Test;

class OrderCursorTest {

    @Test
    void roundTrip() {
        String cursor = OrderCursor.encode(1849203385720832512L);
        assertThat(cursor).doesNotContain("=", "+", "/");
        assertThat(OrderCursor.decode(cursor)).isEqualTo(1849203385720832512L);
    }

    @Test
    void emptyCursorMeansFirstPage() {
        assertThat(OrderCursor.decode(null)).isNull();
        assertThat(OrderCursor.decode("")).isNull();
    }

    @Test
    void illegalCursorIsRejected() {
        assertThatThrownBy(() -> OrderCursor.decode("not-a-cursor")).isInstanceOf(BizException.class);
        // id 不是字符串的 {"id":1}
        assertThatThrownBy(() -> OrderCursor.decode("eyJpZCI6MX0")).isInstanceOf(BizException.class);
    }
}
