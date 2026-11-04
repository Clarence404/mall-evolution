package io.github.clarence404.mall.inventory.reserve;

import io.github.clarence404.mall.common.json.Json;
import io.github.clarence404.mall.common.mq.ConsumerFactory;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 库存服务的消费者：order-paid → confirm，order-cancelled → release。两者都是带旧状态的条件更新，重复消息无副作用。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
class InventoryConsumersConfig {

    @Bean(destroyMethod = "close")
    PushConsumer inventoryConfirmConsumer(
            ClientServiceProvider provider, ClientConfiguration clientConfig, ReservationService reservationService)
            throws ClientException {
        return ConsumerFactory.subscribe(provider, clientConfig, "inventory-confirm-consumer", "order-paid", view -> {
            OrderPaidMessage m = Json.read(view.getBody(), OrderPaidMessage.class);
            try {
                reservationService.confirm(m.orderNo());
                return ConsumeResult.SUCCESS;
            } catch (Exception ex) {
                log.warn("确认预占失败，orderNo={}，第 {} 次投递", m.orderNo(), view.getDeliveryAttempt(), ex);
                return ConsumeResult.FAILURE;
            }
        });
    }

    @Bean(destroyMethod = "close")
    PushConsumer inventoryReleaseConsumer(
            ClientServiceProvider provider, ClientConfiguration clientConfig, ReservationService reservationService)
            throws ClientException {
        return ConsumerFactory.subscribe(
                provider, clientConfig, "inventory-release-consumer", "order-cancelled", view -> {
                    OrderCancelledMessage m = Json.read(view.getBody(), OrderCancelledMessage.class);
                    try {
                        reservationService.release(m.orderNo(), m.lines());
                        return ConsumeResult.SUCCESS;
                    } catch (Exception ex) {
                        log.warn("释放预占失败，orderNo={}，第 {} 次投递", m.orderNo(), view.getDeliveryAttempt(), ex);
                        return ConsumeResult.FAILURE;
                    }
                });
    }

    /** 库存服务自己的消息类型，按 JSON 字段约定反序列化。 */
    record OrderPaidMessage(String orderNo) {}

    record OrderCancelledMessage(String orderNo, List<ReserveLine> lines) {}
}
