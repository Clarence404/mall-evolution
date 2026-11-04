package io.github.clarence404.mall.common.mq;

import org.apache.ibatis.annotations.Mapper;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientConfigurationBuilder;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * rocketmq-client-java 5.x：走 Proxy + gRPC，几个 Bean 就能包装好。
 *
 * <p>ClientServiceProvider 不连网络，始终注册；Producer 与消费者只在 {@code mall.rocketmq.enabled=true} 时创建。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RocketMqProperties.class)
@MapperScan(basePackages = "io.github.clarence404.mall.common.mq", annotationClass = Mapper.class)
public class RocketMqConfig {

    @Bean
    ClientServiceProvider clientServiceProvider() {
        return ClientServiceProvider.loadService();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
    ClientConfiguration rocketMqClientConfiguration(RocketMqProperties props) {
        ClientConfigurationBuilder builder = ClientConfiguration.newBuilder()
                .setEndpoints(props.endpoints())
                .setRequestTimeout(props.requestTimeout());
        if (StringUtils.hasText(props.accessKey())) {
            builder.setCredentialProvider(new StaticSessionCredentialsProvider(props.accessKey(), props.secretKey()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
    Producer rocketMqProducer(ClientServiceProvider provider, ClientConfiguration clientConfig) throws ClientException {
        return provider.newProducerBuilder()
                .setClientConfiguration(clientConfig)
                .build();
    }
}
