package io.github.clarence404.mall.common.demo.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 故障规则的校验、匹配、替换与到期失效；规则删除时指标一起注销，不留下僵尸时间序列。 */
class DemoFaultRegistryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
    private final DemoFaultRegistry registry = new DemoFaultRegistry(meters, clock);

    @Test
    void matchesByMethodAndPathPattern() {
        registry.put("pay-slow", spec("GET", "/internal/payments/*", 100));

        assertThat(registry.match("GET", "/internal/payments/2001")).isNotNull();
        assertThat(registry.match("POST", "/internal/payments/2001")).isNull(); // 方法不对
        assertThat(registry.match("GET", "/internal/payments/2001/close")).isNull(); // * 只匹配一段
        assertThat(registry.match("GET", "/internal/inventory/stocks")).isNull();
    }

    @Test
    void blankMethodMatchesAnyMethod() {
        registry.put("all", spec(null, "/internal/inventory/**", 0));

        assertThat(registry.match("POST", "/internal/inventory/reserve")).isNotNull();
        assertThat(registry.match("GET", "/internal/inventory/stocks")).isNotNull();
    }

    @Test
    void sameNameReplacesAndMetersFollow() {
        registry.put("pay", spec("GET", "/internal/payments/*", 100));
        registry.put("pay", spec("GET", "/internal/payments/*", 400));

        assertThat(registry.list())
                .singleElement()
                .extracting(DemoFaultView::delayMs)
                .isEqualTo(400);
        assertThat(meters.find("demo.fault.waiting").tag("rule", "pay").gauges())
                .hasSize(1);

        registry.remove("pay");
        assertThat(registry.list()).isEmpty();
        assertThat(meters.find("demo.fault.waiting").tag("rule", "pay").gauges())
                .isEmpty();
        assertThat(meters.find("demo.fault.requests").tag("rule", "pay").counters())
                .isEmpty();
    }

    @Test
    void sameConcurrencyUpdatesInPlaceKeepingTheQueue() {
        registry.put("cap", new DemoFaultSpec("GET", "/p/*", 40, null, null, 10, null, null, null));
        DemoFault before = registry.match("GET", "/p/1");

        registry.put("cap", new DemoFaultSpec("GET", "/p/*", 400, null, null, 10, null, null, null));

        DemoFault after = registry.match("GET", "/p/1");
        assertThat(after).isSameAs(before); // 同一个信号量：排队中的请求不会因为调参而消失
        assertThat(after.rule().delayMs()).isEqualTo(400);

        registry.put("cap", new DemoFaultSpec("GET", "/p/*", 400, null, null, 20, null, null, null));
        assertThat(registry.match("GET", "/p/1")).isNotSameAs(before); // 换并发上限才整条替换
    }

    @Test
    void expiresAfterTtl() {
        registry.put("short", new DemoFaultSpec("GET", "/internal/payments/*", 100, null, null, null, null, null, 30));

        clock.advance(Duration.ofSeconds(29));
        assertThat(registry.match("GET", "/internal/payments/1")).isNotNull();

        clock.advance(Duration.ofSeconds(1));
        assertThat(registry.match("GET", "/internal/payments/1")).isNull();
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void defaultTtlIsTenMinutes() {
        DemoFaultView v = registry.put("d", spec("GET", "/x", 0));

        assertThat(v.expiresAt()).isEqualTo(clock.instant().plusSeconds(600));
        assertThat(v.failStatus()).isEqualTo(503);
    }

    @Test
    void rejectsOutOfRangeInsteadOfClamping() {
        assertThatThrownBy(() -> registry.put("x", spec("GET", "/x", 30_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delayMs");
        assertThatThrownBy(() ->
                        registry.put("x", new DemoFaultSpec("GET", "/x", null, 101, null, null, null, null, null)))
                .hasMessageContaining("failPercent");
        assertThatThrownBy(() ->
                        registry.put("x", new DemoFaultSpec("GET", "/x", null, null, 200, null, null, null, null)))
                .hasMessageContaining("failStatus");
        assertThatThrownBy(() ->
                        registry.put("x", new DemoFaultSpec("GET", "/x", null, null, null, null, null, null, 3601)))
                .hasMessageContaining("ttlSeconds");
        assertThatThrownBy(() -> registry.put("Bad Name", spec("GET", "/x", 0))).hasMessageContaining("规则名");
        assertThatThrownBy(() -> registry.put("x", spec("GET", " ", 0))).hasMessageContaining("path");
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void clearRemovesEverything() {
        registry.put("a", spec("GET", "/a", 0));
        registry.put("b", spec("GET", "/b", 0));

        registry.clear();

        assertThat(registry.list()).isEmpty();
        assertThat(meters.getMeters()).isEmpty();
    }

    private static DemoFaultSpec spec(String method, String path, int delayMs) {
        return new DemoFaultSpec(method, path, delayMs, null, null, null, null, null, null);
    }

    /** 可拨动的时钟。 */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
