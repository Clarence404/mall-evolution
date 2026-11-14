package io.github.clarence404.mall.user.points;

import io.github.clarence404.mall.common.json.Json;
import io.github.clarence404.mall.common.mq.ConsumerFactory;
import io.github.clarence404.mall.common.mq.RocketMqProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
class PointsConsumerConfig {

    static final String GROUP = "points-consumer";

    /**
     * 返回 SUCCESS 确认，返回 FAILURE 由 Broker 按退避重投；最大投递 16 次（约 4.7 小时），之后进死信并告警。
     * 和其他消费组一样经 {@link ConsumerFactory} 注册，消费线程数取 mall.rocketmq.consumption-threads。
     */
    @Bean(destroyMethod = "close")
    PushConsumer pointsConsumer(
            ClientServiceProvider provider,
            ClientConfiguration clientConfig,
            RocketMqProperties props,
            PointsGrantService pointsGrantService)
            throws ClientException {
        return ConsumerFactory.subscribe(provider, clientConfig, props, GROUP, "order-paid", view -> {
            OrderPaidEvent e = Json.read(view.getBody(), OrderPaidEvent.class);
            try {
                pointsGrantService.grant(e);
                return ConsumeResult.SUCCESS;
            } catch (Exception ex) {
                log.warn("加积分失败，orderNo={}，第 {} 次投递", e.orderNo(), view.getDeliveryAttempt(), ex);
                return ConsumeResult.FAILURE;
            }
        });
    }
}
