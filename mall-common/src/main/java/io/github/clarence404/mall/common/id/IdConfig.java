package io.github.clarence404.mall.common.id;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
public class IdConfig {

    /**
     * 机器号两种来源，显式配置优先：
     *
     * <ul>
     *   <li>配了 mall.id.worker-id：直接用（本地、测试、阶段一二的两台云主机由环境变量 WORKER_ID 给）
     *   <li>没配：向 Redis 租一个（Kubernetes 起，Pod 名每次发布都变，见 {@link WorkerIdLease}）
     * </ul>
     *
     * 两种都没有就启动失败：机器号不能有默认值，两台机器落到同一个号时同一毫秒内会生成相同的 ID。
     */
    @Bean
    IdGenerator idGenerator(@Value("${mall.id.worker-id:}") String workerId, ObjectProvider<WorkerIdLease> lease) {
        IdGenerator generator;
        if (workerId != null && !workerId.isBlank()) {
            generator = new SnowflakeIdGenerator(Long.parseLong(workerId.trim()));
        } else {
            WorkerIdLease l = lease.getIfAvailable();
            if (l == null) {
                throw new IllegalStateException("未配置雪花 ID 机器号 mall.id.worker-id（生产用环境变量 WORKER_ID 注入，每台机器不同）");
            }
            generator = new LeasedIdGenerator(new SnowflakeIdGenerator(l.acquire()), l);
        }
        Ids.use(generator);
        return generator;
    }

    /** 只在没有显式机器号时创建；没有 Redis 也启动失败。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${mall.id.worker-id:}'.isBlank()")
    WorkerIdLease workerIdLease(
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${spring.application.name:application}") String service,
            @Value("${mall.id.lease-ttl:30s}") String ttl,
            @Value("${mall.id.lease-renew-interval:10s}") String renewInterval) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException(
                    "未配置雪花 ID 机器号 mall.id.worker-id，也没有可用的 Redis 来租用机器号（生产用环境变量 WORKER_ID 或 Redis 租约）");
        }
        return new WorkerIdLease(
                template,
                service,
                hostname(),
                DurationStyle.detectAndParse(ttl),
                DurationStyle.detectAndParse(renewInterval));
    }

    /** Kubernetes 里 HOSTNAME 就是 Pod 名。 */
    private static String hostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
