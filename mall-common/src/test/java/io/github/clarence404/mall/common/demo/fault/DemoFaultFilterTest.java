package io.github.clarence404.mall.common.demo.fault;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 过滤器按规则注入：延迟、按比例失败、并发上限（排队）、响应迟到；没命中的请求原样通过。 */
class DemoFaultFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DemoFaultRegistry registry = new DemoFaultRegistry(meters);
    private final DemoFaultFilter filter = new DemoFaultFilter(registry);

    @Test
    void unmatchedRequestPassesUntouched() throws Exception {
        registry.put("pay", spec("/internal/payments/*", 0, 100, null, null));
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicInteger handled = new AtomicInteger();

        filter.doFilter(get("/internal/inventory/stocks"), res, (rq, rs) -> handled.incrementAndGet());

        assertThat(handled).hasValue(1);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void failPercent100AlwaysFailsWithoutReachingHandler() throws Exception {
        registry.put("pay", spec("/internal/payments/*", 0, 100, null, null));
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicInteger handled = new AtomicInteger();

        filter.doFilter(get("/internal/payments/1"), res, (rq, rs) -> handled.incrementAndGet());

        assertThat(handled).hasValue(0);
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getHeader(DemoFaultFilter.HEADER)).isEqualTo("pay");
        assertThat(res.getContentAsString()).contains("DEMO_FAULT");
        assertThat(meters.get("demo.fault.requests")
                        .tag("rule", "pay")
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void delayHappensBeforeHandler() throws Exception {
        registry.put("slow", spec("/internal/payments/*", 150, 0, null, null));
        long start = System.nanoTime();

        filter.doFilter(get("/internal/payments/1"), new MockHttpServletResponse(), (rq, rs) -> {});

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(150);
        assertThat(meters.get("demo.fault.requests")
                        .tag("rule", "slow")
                        .tag("outcome", "passed")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void delayPercentZeroSkipsBothDelays() throws Exception {
        registry.put("sampled", new DemoFaultSpec(null, "/api/orders", 2_000, 0, null, null, 2_000, 0, null));
        long start = System.nanoTime();

        filter.doFilter(
                new MockHttpServletRequest("POST", "/api/orders"), new MockHttpServletResponse(), (rq, rs) -> {});

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1_000);
    }

    @Test
    void maxConcurrentQueuesTheRest() throws Exception {
        registry.put("cap", spec("/internal/payments/*", 0, 0, 1, null));
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        FilterChain blocking = (rq, rs) -> {
            firstInside.countDown();
            await(releaseFirst);
        };

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> run(blocking));
        assertThat(firstInside.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> run((rq, rs) -> {}));

        DemoFault fault = registry.match("GET", "/internal/payments/1");
        waitUntil(() -> fault.waiting() == 1); // 第二个请求在排队
        assertThat(fault.inflight()).isEqualTo(1);
        assertThat(second).isNotDone();

        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertThat(fault.inflight()).isZero();
        assertThat(fault.waiting()).isZero();
    }

    @Test
    void responseDelayHoldsTheBodyUntilAfterHandling() throws Exception {
        registry.put("late", spec("/api/orders", 0, 0, null, 150));
        MockHttpServletResponse res = new MockHttpServletResponse();
        long start = System.nanoTime();

        filter.doFilter(
                new MockHttpServletRequest("POST", "/api/orders"),
                res,
                (rq, rs) -> rs.getWriter().write("{\"orderNo\":\"1\"}"));

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(150);
        assertThat(res.getContentAsString()).isEqualTo("{\"orderNo\":\"1\"}"); // 响应体完整发出
    }

    private void run(FilterChain chain) {
        try {
            filter.doFilter(get("/internal/payments/1"), new MockHttpServletResponse(), chain);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private static DemoFaultSpec spec(
            String path, int delayMs, int failPercent, Integer maxConcurrent, Integer responseDelayMs) {
        return new DemoFaultSpec(null, path, delayMs, failPercent, null, maxConcurrent, responseDelayMs, null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("等待条件超时").isLessThan(deadline);
            Thread.sleep(10);
        }
    }
}
