package io.github.clarence404.mall.common.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;

/**
 * 每一次失败的尝试都记一次 remote_retryable_failures_total，重试用完仍失败再记一次 remote_retryable_exhausted_total；重试参数用占位符从配置读（和 PaymentQueryClient 的写法一样），
 * 不配时用注解里的默认值，配了 delay=0 也能正常工作（重试风暴实验的对照组就是「不退避」）。
 */
class RetryMetricsTest {

    @Test
    void countsEveryFailedAttempt() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            Flaky flaky = ctx.getBean(Flaky.class);
            flaky.failFirst(2);

            assertThat(flaky.call()).isEqualTo("ok");

            assertThat(flaky.attempts()).isEqualTo(3);
            assertThat(failures(ctx)).isEqualTo(2);
            assertThat(ctx.getBean(MeterRegistry.class)
                            .find(RetryMetrics.EXHAUSTED)
                            .counters())
                    .isEmpty(); // 最后成功了，没有放弃
        }
    }

    @Test
    void exhaustedRetriesAreCountedToo() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            Flaky flaky = ctx.getBean(Flaky.class);
            flaky.failFirst(100);

            assertThatThrownBy(flaky::call).isInstanceOf(IllegalStateException.class);

            assertThat(flaky.attempts()).isEqualTo(3); // 默认 max-retries=2：1 次 + 2 次重试
            assertThat(failures(ctx)).isEqualTo(3);
            assertThat(ctx.getBean(MeterRegistry.class)
                            .get(RetryMetrics.EXHAUSTED)
                            .tag("method", "Flaky.call")
                            .counter()
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void retryCountComesFromConfiguration() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of("test.retry.max-retries", "0"))); // 不重试
            ctx.register(Config.class);
            ctx.refresh();
            Flaky flaky = ctx.getBean(Flaky.class);
            flaky.failFirst(100);

            assertThatThrownBy(flaky::call).isInstanceOf(IllegalStateException.class);

            assertThat(flaky.attempts()).isEqualTo(1);
        }
    }

    private static AnnotationConfigApplicationContext context() {
        return new AnnotationConfigApplicationContext(Config.class);
    }

    private static double failures(AnnotationConfigApplicationContext ctx) {
        return ctx.getBean(MeterRegistry.class)
                .get(RetryMetrics.FAILURES)
                .tag("method", "Flaky.call")
                .tag("exception", "IllegalStateException")
                .counter()
                .count();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableResilientMethods
    static class Config {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        RetryMetrics retryMetrics(MeterRegistry registry) {
            return new RetryMetrics(registry);
        }

        @Bean
        Flaky flaky() {
            return new Flaky();
        }
    }

    /** 被 @Retryable 代理（CGLIB 子类）：状态只能经方法访问，代理对象上的字段是空的。 */
    static class Flaky {
        private final AtomicInteger failuresBeforeSuccess = new AtomicInteger();
        private final AtomicInteger attempts = new AtomicInteger();

        public void failFirst(int times) {
            failuresBeforeSuccess.set(times);
        }

        public int attempts() {
            return attempts.get();
        }

        @Retryable(
                includes = IllegalStateException.class,
                maxRetriesString = "${test.retry.max-retries:2}",
                delayString = "${test.retry.delay:0ms}",
                jitterString = "${test.retry.jitter:0ms}")
        public String call() {
            attempts.incrementAndGet();
            if (failuresBeforeSuccess.getAndDecrement() > 0) {
                throw new IllegalStateException("模拟下游超时");
            }
            return "ok";
        }
    }
}
