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
 *
 * <p>消费线程数每个消费组都显式设置（{@link RocketMqProperties#consumptionThreadsFor}）：客户端的默认值是每个消费者 20 个线程，
 * 而每条消息的处理都要占一个数据库连接，消费组一多，消息堆积时消费线程就会把连接池占满、挤掉接口请求。
 */
public final class ConsumerFactory {

    private ConsumerFactory() {}

    public static PushConsumer subscribe(
            ClientServiceProvider provider,
            ClientConfiguration clientConfig,
            RocketMqProperties props,
            String consumerGroup,
            String topic,
            Function<MessageView, ConsumeResult> listener)
            throws ClientException {
        return provider.newPushConsumerBuilder()
                .setClientConfiguration(clientConfig)
                .setConsumerGroup(consumerGroup)
                .setSubscriptionExpressions(Map.of(topic, new FilterExpression("*", FilterExpressionType.TAG)))
                .setConsumptionThreadCount(props.consumptionThreadsFor(consumerGroup)) // 不设就是客户端默认的 20
                .setMessageListener(listener::apply)
                .build();
    }
}
