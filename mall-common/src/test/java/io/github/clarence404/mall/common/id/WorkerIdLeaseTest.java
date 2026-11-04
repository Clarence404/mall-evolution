package io.github.clarence404.mall.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** 租约逻辑（Redis 用替身、时钟可控）：真实 Redis 上的行为见 inventory-service 的 WorkerIdLeaseIT。 */
class WorkerIdLeaseTest {

    StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);

    long[] now = {1_800_000_000_000L};
    WorkerIdLease lease =
            new WorkerIdLease(redis, "svc", "pod-a", Duration.ofSeconds(30), Duration.ofHours(1), () -> now[0]);
    IdGenerator generator;

    @BeforeEach
    void acquire() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        generator = new LeasedIdGenerator(new SnowflakeIdGenerator(lease.acquire()), lease);
    }

    @AfterEach
    void close() {
        lease.close();
    }

    @Test
    void expiredLeaseMakesGeneratorRefuse() {
        assertThat(generator.nextId()).isPositive();

        now[0] += 30_001; // 一直没续上，租约过期

        assertThatThrownBy(generator::nextId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void leaseTakenByAnotherInstanceMakesGeneratorRefuseImmediately() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L); // 键已不是自己的

        lease.renew();

        assertThatThrownBy(generator::nextId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisErrorDuringRenewalKeepsIssuingUntilLeaseExpires() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new QueryTimeoutException("Redis command timed out"));
        now[0] += 10_000;

        lease.renew(); // 续约失败，但租约还有 20 秒

        assertThat(generator.nextId()).isPositive();
        now[0] += 20_001;
        assertThatThrownBy(generator::nextId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulRenewalExtendsLease() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        now[0] += 25_000;

        lease.renew();
        now[0] += 25_000; // 距首次租到 50 秒，续约后仍有效

        assertThat(generator.nextId()).isPositive();
    }

    @Test
    void acquireSkipsWorkerIdsHeldByOthers() {
        WorkerIdLease second =
                new WorkerIdLease(redis, "svc", "pod-a", Duration.ofSeconds(30), Duration.ofHours(1), () -> now[0]);
        int first = lease.workerId();
        when(ops.setIfAbsent(eq(lease.key(first)), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThat(second.acquire()).isEqualTo((first + 1) % WorkerIdLease.MAX_WORKERS);
        second.close();
    }
}
