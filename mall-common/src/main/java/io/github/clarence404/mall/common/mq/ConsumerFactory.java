package io.github.clarence404.mall.common.mq;

import java.util.Map;
import java.util.function.Function;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;

/**
 * 注册 PushConsumer 的小工具：返回 SUCCESS 确认，返回 FAILURE 由 Broker 按退避重投，超过次数进 %DLQ%{消费组}。
 */
public final class ConsumerFactory {

    private ConsumerFactory() {}

    public static PushConsumer subscribe(
            ClientServiceProvider provider,
            ClientConfiguration clientConfig,
            String consumerGroup,
            String topic,
            Function<MessageView, ConsumeResult> listener)
            throws ClientException {
        return provider.newPushConsumerBuilder()
                .setClientConfiguration(clientConfig)
                .setConsumerGroup(consumerGroup)
                .setSubscriptionExpressions(Map.of(topic, new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(listener::apply)
                .build();
    }
}
