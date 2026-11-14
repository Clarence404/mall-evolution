package io.github.clarence404.mall.common.mq;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 5 接入点与凭据：从环境变量读取（阶段二 EnvironmentFile，阶段三起 Kubernetes Secret），不进仓库。
 *
 * @param enabled 关掉后不创建 Producer 与消费者，业务事务里照常写 t_outbox，恢复后由中继补发
 * @param requestTimeout 同步发送等 Broker 确认的上限，默认 1 秒：Broker 故障时中继线程最多卡这么久，宁可快速失败走退避重试
 * @param consumptionThreads 每个消费组的消费线程数，默认 4。rocketmq-client-java 5.2.2 不设时每个 PushConsumer 是 20 个线程，
 *     mall-app 有 5 个消费组就是 100 个线程去抢 20 个数据库连接，所以必须显式给。一个进程里「消费组数 × 线程数」不要超过连接池的一半，
 *     另一半留给接口请求
 * @param consumptionThreadsByGroup 个别消费组单独设线程数（键是消费组名），没写的组用 consumptionThreads
 */
@ConfigurationProperties(prefix = "mall.rocketmq")
public record RocketMqProperties(
        boolean enabled,
        String endpoints,
        String accessKey,
        String secretKey,
        Duration requestTimeout,
        Integer consumptionThreads,
        Map<String, Integer> consumptionThreadsByGroup) {

    /** 没配置时每个消费组的线程数。 */
    public static final int DEFAULT_CONSUMPTION_THREADS = 4;

    public RocketMqProperties {
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(1);
        }
        if (consumptionThreads == null) {
            consumptionThreads = DEFAULT_CONSUMPTION_THREADS;
        }
        consumptionThreadsByGroup =
                consumptionThreadsByGroup == null ? Map.of() : Map.copyOf(consumptionThreadsByGroup);
        checkThreads("mall.rocketmq.consumption-threads", consumptionThreads);
        consumptionThreadsByGroup.forEach(
                (group, n) -> checkThreads("mall.rocketmq.consumption-threads-by-group." + group, n));
    }

    /** 某个消费组用几个消费线程：单独配了就用单独的，否则用统一的默认值。 */
    public int consumptionThreadsFor(String consumerGroup) {
        return consumptionThreadsByGroup.getOrDefault(consumerGroup, consumptionThreads);
    }

    private static void checkThreads(String key, Integer n) {
        if (n == null || n < 1 || n > 64) {
            throw new IllegalArgumentException(key + " 应在 1–64 之间，当前为 " + n);
        }
    }
}
