package io.github.clarence404.mall.payment.pay;

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
class PaymentConsumersConfig {

    @Bean(destroyMethod = "close")
    PushConsumer paymentRefundConsumer(
            ClientServiceProvider provider,
            ClientConfiguration clientConfig,
            RocketMqProperties props,
            RefundService refundService)
            throws ClientException {
        return ConsumerFactory.subscribe(
                provider, clientConfig, props, RefundService.GROUP, "order-refund-required", view -> {
                    RefundRequired m = Json.read(view.getBody(), RefundRequired.class);
                    try {
                        refundService.refund(m.orderNo(), m.paymentNo());
                        return ConsumeResult.SUCCESS;
                    } catch (Exception ex) {
                        log.warn("发起退款失败，orderNo={}，第 {} 次投递", m.orderNo(), view.getDeliveryAttempt(), ex);
                        return ConsumeResult.FAILURE;
                    }
                });
    }

    /** 订单侧按 payment-succeeded 原样转发，取订单号和要退的那张支付单号。 */
    record RefundRequired(String orderNo, String paymentNo) {}
}
