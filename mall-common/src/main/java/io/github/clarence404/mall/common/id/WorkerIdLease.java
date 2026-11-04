package io.github.clarence404.mall.common.id;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 雪花机器号租约（Kubernetes 起）：Pod 名每次发布都变，机器号不能再写死在配置里，改为启动时向 Redis 租一个。
 *
 * <ul>
 *   <li>租：从主机名的哈希开始，依次对 0–1023 执行 {@code SET mall:snowflake:worker:{服务}:{号} 实例ID NX PX ttl}，抢到为止
 *   <li>续：每 renewInterval 续一次（键的值还是自己才 PEXPIRE）；续约失败只要没过期就继续发号，过期了就停
 *   <li>丢：续约时发现键已被别的实例占了，立即标记失效
 *   <li>还：停机时删除自己的键（值还是自己才删）
 * </ul>
 *
 * <p>租约失效后 {@link LeasedIdGenerator} 拒绝发号：宁可下单失败，也不冒和别的实例撞号的风险。
 */
@Slf4j
public class WorkerIdLease implements AutoCloseable {

    static final int MAX_WORKERS = (int) SnowflakeIdGenerator.MAX_WORKER_ID + 1;

    private static final RedisScript<Long> RENEW = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final String service;
    private final String hostname;
    private final String instanceId;
    private final Duration ttl;
    private final Duration renewInterval;
    private final LongSupplier clock;

    private volatile int workerId = -1;
    private volatile long validUntil;
    private volatile boolean lost;
    private ScheduledExecutorService renewer;

    public WorkerIdLease(
            StringRedisTemplate redis, String service, String hostname, Duration ttl, Duration renewInterval) {
        this(redis, service, hostname, ttl, renewInterval, System::currentTimeMillis);
    }

    WorkerIdLease(
            StringRedisTemplate redis,
            String service,
            String hostname,
            Duration ttl,
            Duration renewInterval,
            LongSupplier clock) {
        this.redis = redis;
        this.service = service;
        this.hostname = hostname;
        this.instanceId = hostname + "/" + Long.toHexString(System.nanoTime());
        this.ttl = ttl;
        this.renewInterval = renewInterval;
        this.clock = clock;
    }

    /** 租一个机器号并开始定时续约；1024 个号都被占用时启动失败。 */
    public synchronized int acquire() {
        int start = Math.floorMod(hostname.hashCode(), MAX_WORKERS); // 从主机名的哈希开始找，各实例尽量不在同一处争抢
        for (int i = 0; i < MAX_WORKERS; i++) {
            int id = (start + i) % MAX_WORKERS;
            long now = clock.getAsLong();
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(id), instanceId, ttl))) {
                workerId = id;
                validUntil = now + ttl.toMillis();
                startRenewal();
                log.info("租到雪花机器号 {}（服务 {}，实例 {}）", id, service, instanceId);
                return id;
            }
        }
        throw new IllegalStateException("服务 " + service + " 的 " + MAX_WORKERS + " 个雪花机器号都已被占用");
    }

    /** 续约一次：键还是自己的就延长；被别人占了立即失效；Redis 出错只记日志，等租约自然过期。 */
    void renew() {
        long start = clock.getAsLong();
        try {
            Long renewed = redis.execute(RENEW, List.of(key(workerId)), instanceId, String.valueOf(ttl.toMillis()));
            if (renewed != null && renewed == 1L) {
                validUntil = start + ttl.toMillis();
            } else {
                lost = true;
                log.error("雪花机器号 {} 的租约已丢失（键过期或被别的实例占用），停止发号", workerId);
            }
        } catch (RuntimeException e) {
            log.warn("雪花机器号 {} 续约失败，{} 毫秒后租约到期", workerId, validUntil - start, e);
        }
    }

    /** 租约是否仍然有效：没丢、没过期。 */
    public boolean isValid() {
        return workerId >= 0 && !lost && clock.getAsLong() < validUntil;
    }

    public int workerId() {
        return workerId;
    }

    private void startRenewal() {
        renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "worker-id-lease");
            t.setDaemon(true);
            return t;
        });
        long period = renewInterval.toMillis();
        renewer.scheduleAtFixedRate(this::renew, period, period, TimeUnit.MILLISECONDS);
    }

    /** 停机时停止续约并归还机器号。 */
    @Override
    public synchronized void close() {
        if (renewer != null) {
            renewer.shutdownNow();
        }
        if (workerId >= 0 && !lost) {
            try {
                redis.execute(RELEASE, List.of(key(workerId)), instanceId);
            } catch (RuntimeException e) {
                log.warn("归还雪花机器号 {} 失败，等租约自然过期", workerId, e);
            }
        }
        lost = true;
    }

    String key(int id) {
        return "mall:snowflake:worker:" + service + ":" + id;
    }
}
