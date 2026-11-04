package io.github.clarence404.mall.order.mq;

import io.github.clarence404.mall.common.json.Json;
import io.github.clarence404.mall.common.mq.ConsumerFactory;
import io.github.clarence404.mall.order.cancel.OrderCloseService;
import io.github.clarence404.mall.order.cancel.OrderTimeoutMessage;
import io.github.clarence404.mall.order.notify.PaidNotifier;
import io.github.clarence404.mall.order.paid.OrderPaidEvent;
import io.github.clarence404.mall.order.paid.OrderPaidTxService;
import io.github.clarence404.mall.order.paid.PaymentSucceeded;
import io.github.clarence404.mall.order.shipment.ShipmentCreator;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mall-order 的消费者：order-paid 的 notify-consumer、shipment-consumer，payment-succeeded 的 order-payment-consumer，
 * 以及超时关单 order-timeout-consumer。
 *
 * <p>四个组都用并发消费，失败按阶梯退避重投，最大投递次数与死信在控制台按组配置（见 deploy/rocketmq/init-topics.sh）。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mall.rocketmq", name = "enabled", havingValue = "true")
class OrderConsumersConfig {

    @Bean(destroyMethod = "close")
    PushConsumer notifyConsumer(ClientServiceProvider provider, ClientConfiguration clientConfig, PaidNotifier notifier)
            throws ClientException {
        return ConsumerFactory.subscribe(
                provider,
                clientConfig,
                "notify-consumer",
                "order-paid",
                listener(OrderPaidEvent.class, notifier::onPaid));
    }

    @Bean(destroyMethod = "close")
    PushConsumer shipmentConsumer(
            ClientServiceProvider provider, ClientConfiguration clientConfig, ShipmentCreator creator)
            throws ClientException {
        return ConsumerFactory.subscribe(
                provider,
                clientConfig,
                "shipment-consumer",
                "order-paid",
                listener(OrderPaidEvent.class, creator::onPaid));
    }

    /**
     * 支付成功 → 订单待发货：「支付成功，订单确认中」这个一致性窗口就是它的消费延迟，告警单独收紧到 1 分钟。
     */
    @Bean(destroyMethod = "close")
    PushConsumer orderPaymentConsumer(
            ClientServiceProvider provider, ClientConfiguration clientConfig, OrderPaidTxService orderPaidTxService)
            throws ClientException {
        return ConsumerFactory.subscribe(
                provider,
                clientConfig,
                "order-payment-consumer",
                "payment-succeeded",
                listener(PaymentSucceeded.class, orderPaidTxService::markPaid));
    }

    /**
     * 到期后先关支付单，再用状态条件更新 10→50，影响 1 行才写 order-cancelled（库存服务消费后 release 预占）；
     * 用户已支付时直接确认，支付成功不需要「撤回」定时消息。
     */
    @Bean(destroyMethod = "close")
    PushConsumer orderTimeoutConsumer(
            ClientServiceProvider provider, ClientConfiguration clientConfig, OrderCloseService closeService)
            throws ClientException {
        return ConsumerFactory.subscribe(
                provider,
                clientConfig,
                "order-timeout-consumer",
                "order-timeout",
                listener(OrderTimeoutMessage.class, m -> closeService.closeIfUnpaid(m.orderNo())));
    }

    private static <T> java.util.function.Function<MessageView, ConsumeResult> listener(
            Class<T> type, Consumer<T> handler) {
        return view -> {
            try {
                handler.accept(Json.read(view.getBody(), type));
                return ConsumeResult.SUCCESS;
            } catch (Exception ex) {
                log.warn(
                        "消费失败，topic={}，keys={}，第 {} 次投递",
                        view.getTopic(),
                        view.getKeys(),
                        view.getDeliveryAttempt(),
                        ex);
                return ConsumeResult.FAILURE;
            }
        };
    }
}
