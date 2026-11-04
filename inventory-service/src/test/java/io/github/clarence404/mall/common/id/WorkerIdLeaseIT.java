package io.github.clarence404.mall.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 雪花机器号租约在真实 Redis 8 上：两个实例拿到不同的号；租约丢失或过期后拒绝发号；停机归还。需要 Docker。 */
@Testcontainers(disabledWithoutDocker = true)
class WorkerIdLeaseIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    void flush() {
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void twoInstancesGetDifferentWorkerIds() {
        // 同一个主机名 → 从同一个位置开始找，第二个实例必须跳过已被占用的号
        try (WorkerIdLease a = lease("pod-a", Duration.ofSeconds(30));
                WorkerIdLease b = lease("pod-a", Duration.ofSeconds(30))) {
            int idA = a.acquire();
            int idB = b.acquire();

            assertThat(idA).isNotEqualTo(idB);
            assertThat(new LeasedIdGenerator(new SnowflakeIdGenerator(idA), a).nextId())
                    .isNotEqualTo(new LeasedIdGenerator(new SnowflakeIdGenerator(idB), b).nextId());
        }
    }

    @Test
    void lostLeaseMakesGeneratorRefuse() {
        try (WorkerIdLease a = lease("pod-a", Duration.ofSeconds(30))) {
            IdGenerator generator = new LeasedIdGenerator(new SnowflakeIdGenerator(a.acquire()), a);
            assertThat(generator.nextId()).isPositive();

            redis.opsForValue().set(a.key(a.workerId()), "someone-else"); // 例如长时间 GC 后键过期又被别人租走
            a.renew();

            assertThatThrownBy(generator::nextId).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void expiredLeaseWithoutRenewalMakesGeneratorRefuse() throws Exception {
        try (WorkerIdLease a = new WorkerIdLease(redis, "svc", "pod-a", Duration.ofMillis(500), Duration.ofHours(1))) {
            IdGenerator generator = new LeasedIdGenerator(new SnowflakeIdGenerator(a.acquire()), a);
            assertThat(generator.nextId()).isPositive();

            Thread.sleep(700); // 续约线程没机会跑（间隔 1 小时），租约到期

            assertThatThrownBy(generator::nextId).isInstanceOf(IllegalStateException.class);
            assertThat(redis.hasKey(a.key(a.workerId()))).isFalse();
        }
    }

    @Test
    void renewalKeepsLeaseAliveAndCloseReleasesWorkerId() throws Exception {
        WorkerIdLease a = new WorkerIdLease(redis, "svc", "pod-a", Duration.ofMillis(600), Duration.ofMillis(150));
        int id = a.acquire();
        Thread.sleep(1500); // 远超 ttl，靠续约活着

        assertThat(a.isValid()).isTrue();
        a.close();
        assertThat(redis.hasKey(a.key(id))).isFalse(); // 停机归还，新实例可以立刻用这个号

        try (WorkerIdLease b = lease("pod-a", Duration.ofSeconds(30))) {
            assertThat(b.acquire()).isEqualTo(id);
        }
    }

    private static WorkerIdLease lease(String hostname, Duration ttl) {
        return new WorkerIdLease(redis, "svc", hostname, ttl, Duration.ofSeconds(10));
    }
}
