package io.github.clarence404.mall.product.detail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 每个实例都订阅失效频道，收到后删自己的 L1；自己发的广播自己也会收到，所以 evict 里不单独删本地。
 *
 * <p>Pub/Sub 不保证送达，断连期间的广播会丢，L1 的 30 秒 TTL 就是旧数据的上限。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mall.cache", name = "l1-invalidation-enabled", matchIfMissing = true)
class ProductCacheSubscriber {

    @Bean
    RedisMessageListenerContainer productCacheListenerContainer(
            RedisConnectionFactory connectionFactory, ProductDetailService detailService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    long skuId = Long.parseLong(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
                    detailService.evictLocal(skuId);
                    log.debug("收到失效广播，删除 L1，skuId={}", skuId);
                },
                new ChannelTopic(ProductCacheInvalidator.CHANNEL));
        return container;
    }
}
