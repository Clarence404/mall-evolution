package io.github.clarence404.mall.user.points;

import io.github.clarence404.mall.common.json.Json;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
class PointsConsumerConfig {

    /** 返回 SUCCESS 确认，返回 FAILURE 由 Broker 按退避重投；最大投递 16 次（约 4.7 小时），之后进死信并告警。 */
    @Bean(destroyMethod = "close")
    PushConsumer pointsConsumer(
            ClientServiceProvider provider, ClientConfiguration clientConfig, PointsGrantService pointsGrantService)
            throws ClientException {
        return provider.newPushConsumerBuilder()
                .setClientConfiguration(clientConfig)
                .setConsumerGroup("points-consumer")
                .setSubscriptionExpressions(Map.of("order-paid", new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(view -> {
                    OrderPaidEvent e = Json.read(view.getBody(), OrderPaidEvent.class);
                    try {
                        pointsGrantService.grant(e);
                        return ConsumeResult.SUCCESS;
                    } catch (Exception ex) {
                        log.warn("加积分失败，orderNo={}，第 {} 次投递", e.orderNo(), view.getDeliveryAttempt(), ex);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
    }
}
