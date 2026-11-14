package io.github.clarence404.mall.common.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.PushConsumerBuilder;
import org.junit.jupiter.api.Test;

/**
 * 每个消费组都显式设置消费线程数：rocketmq-client-java 5.2.2 不设时是 20（PushConsumerBuilderImpl 的默认值），
 * 5 个消费组就是 100 个线程抢 20 个数据库连接。
 */
class ConsumerFactoryTest {

    ClientServiceProvider provider = mock(ClientServiceProvider.class);
    PushConsumerBuilder builder = mock(PushConsumerBuilder.class, RETURNS_SELF); // 链式调用都返回自己
    ClientConfiguration clientConfig = mock(ClientConfiguration.class);

    @Test
    void consumptionThreadCountIsAlwaysSetExplicitly() throws Exception {
        when(provider.newPushConsumerBuilder()).thenReturn(builder);
        RocketMqProperties props = props(null, null);

        ConsumerFactory.subscribe(
                provider, clientConfig, props, "points-consumer", "order-paid", v -> ConsumeResult.SUCCESS);

        verify(builder).setConsumerGroup("points-consumer");
        verify(builder).setConsumptionThreadCount(4); // 没配置时的默认值，而不是客户端的 20
        verify(builder).build();
    }

    @Test
    void perGroupOverrideWinsOverTheDefault() throws Exception {
        when(provider.newPushConsumerBuilder()).thenReturn(builder);
        RocketMqProperties props = props(2, Map.of("order-payment-consumer", 6));

        ConsumerFactory.subscribe(
                provider,
                clientConfig,
                props,
                "order-payment-consumer",
                "payment-succeeded",
                v -> ConsumeResult.SUCCESS);

        verify(builder).setConsumptionThreadCount(6);
        assertThat(props.consumptionThreadsFor("notify-consumer")).isEqualTo(2);
    }

    @Test
    void nonsensicalThreadCountsAreRejectedAtStartup() {
        assertThatThrownBy(() -> props(0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mall.rocketmq.consumption-threads");
        assertThatThrownBy(() -> props(4, Map.of("points-consumer", 500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("points-consumer");
    }

    private static RocketMqProperties props(Integer threads, Map<String, Integer> byGroup) {
        return new RocketMqProperties(true, "localhost:8081", null, null, null, threads, byGroup);
    }
}
