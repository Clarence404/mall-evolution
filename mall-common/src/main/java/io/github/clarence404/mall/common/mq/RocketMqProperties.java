package io.github.clarence404.mall.common.mq;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 5 接入点与凭据：从环境变量读取（阶段二 EnvironmentFile，阶段三起 Kubernetes Secret），不进仓库。
 *
 * @param enabled 关掉后不创建 Producer 与消费者，业务事务里照常写 t_outbox，恢复后由中继补发
 * @param requestTimeout 同步发送等 Broker 确认的上限，默认 1 秒：Broker 故障时中继线程最多卡这么久，宁可快速失败走退避重试
 */
@ConfigurationProperties(prefix = "mall.rocketmq")
public record RocketMqProperties(
        boolean enabled, String endpoints, String accessKey, String secretKey, Duration requestTimeout) {

    public RocketMqProperties {
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(1);
        }
    }
}
