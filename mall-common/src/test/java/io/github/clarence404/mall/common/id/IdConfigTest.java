package io.github.clarence404.mall.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class IdConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(IdConfig.class);

    @Test
    void startupFailsWhenWorkerIdMissing() {
        runner.run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("mall.id.worker-id"));
    }

    @Test
    void startupFailsWhenWorkerIdBlank() {
        runner.withPropertyValues("mall.id.worker-id=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startupFailsWhenWorkerIdOutOfRange() {
        runner.withPropertyValues("mall.id.worker-id=1024")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void createsGeneratorWhenWorkerIdConfigured() {
        runner.withPropertyValues("mall.id.worker-id=2")
                .run(context -> assertThat(context).hasSingleBean(IdGenerator.class));
    }

    @Test
    void explicitWorkerIdBypassesRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        runner.withBean(StringRedisTemplate.class, () -> redis)
                .withPropertyValues("mall.id.worker-id=3")
                .run(context -> {
                    assertThat(context).hasSingleBean(IdGenerator.class).doesNotHaveBean(WorkerIdLease.class);
                    assertThat(context.getBean(IdGenerator.class)).isInstanceOf(SnowflakeIdGenerator.class);
                });
        verify(redis, never()).opsForValue(); // 没去 Redis 租号
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingWorkerIdLeasesOneFromRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        runner.withBean(StringRedisTemplate.class, () -> redis)
                .withPropertyValues("spring.application.name=inventory-service")
                .run(context -> {
                    assertThat(context).hasSingleBean(WorkerIdLease.class);
                    assertThat(context.getBean(IdGenerator.class)).isInstanceOf(LeasedIdGenerator.class);
                    assertThat(context.getBean(IdGenerator.class).nextId()).isPositive();
                });
        verify(ops)
                .setIfAbsent(startsWith("mall:snowflake:worker:inventory-service:"), anyString(), any(Duration.class));
    }
}
