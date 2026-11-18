package io.github.clarence404.mall.common.mq;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 给每个消费组的监听器包一层：记录端到端的消费延迟 {@code mq.consume.delay}（Prometheus 里是 mq_consume_delay_seconds_*）。
 *
 * <p>消费延迟 = 这条消息处理完的时刻 − 它「该被消费」的时刻，按消费组（group）和处理结果（result=success|failure）分开记：
 *
 * <ul>
 *   <li>普通消息：从消息的产生时间（born timestamp，生产者发送的时刻）算起，包含在 Broker 里排队、投递到消费者、处理三段
 *   <li>定时消息（order-timeout）：从它定好的投递时间算起，否则 15 分钟的支付超时本身会被算成「延迟」；
 *       拿不到投递时间时退回产生时间
 *   <li>重投的消息仍从最初的时刻算：失败后退避重投，延迟自然变长，这正是用户感受到的
 * </ul>
 *
 * <p>它和 Broker 侧的堆积量（rocketmq_group_diff）互补：堆积量说「还有多少条没消费」，这里说「消费到的消息晚了多久」，
 * 而且不依赖 rocketmq-exporter，本机起服务就有。
 *
 * <p>精度：产生时间是生产者机器的时钟，处理完的时刻是本机的时钟，两台机器的时钟差会原样带进来；定时消息的投递时刻由 Broker 按自己的时钟
 * 判断，而且只精确到秒级。算出负数一律按 0 记——本地实测定时消息的读数大多是 0（按本机时钟，消息在定好的时刻之前就处理完了），
 * 所以定时消息的读数在「晚了不到 1 秒」这个量级上不可信，它能回答的是「有没有晚几秒、几十秒」。
 *
 * <p>另有一个只在演示环境生效的回调点 {@link ConsumeHold}，正式环境是空操作。
 */
@Component
public class ConsumeInstrumentation {

    /** 指标名。 */
    public static final String DELAY_METRIC = "mq.consume.delay";

    private final MeterRegistry registry;
    private final LongSupplier clockMillis;
    private ConsumeHold hold = ConsumeHold.NONE;

    @Autowired
    public ConsumeInstrumentation(MeterRegistry registry) {
        this(registry, System::currentTimeMillis);
    }

    /** 测试用：传入可拨动的时钟（epoch 毫秒）。 */
    ConsumeInstrumentation(MeterRegistry registry, LongSupplier clockMillis) {
        this.registry = registry;
        this.clockMillis = clockMillis;
    }

    /** 只有演示环境（demo profile）注册了实现，用来在处理每条消息前多停留一会儿（高并发实验 G 的消费能力模型）。 */
    @Autowired(required = false)
    void setHold(ConsumeHold hold) {
        this.hold = hold;
    }

    /**
     * 包装一个消费组的监听器。两个计时器在这里就注册好：还没消费过消息的组在 Prometheus 里也有值为 0 的序列，rate() 从一开始就有数。
     */
    public Function<MessageView, ConsumeResult> wrap(
            String consumerGroup, Function<MessageView, ConsumeResult> listener) {
        Timer success = timer(consumerGroup, "success");
        Timer failure = timer(consumerGroup, "failure");
        return view -> {
            hold.beforeConsume(consumerGroup); // 正式环境是空操作
            ConsumeResult result = ConsumeResult.FAILURE; // 监听器抛异常时客户端同样按失败处理、稍后重投
            try {
                result = listener.apply(view);
                return result;
            } finally {
                long delayMs = Math.max(0, clockMillis.getAsLong() - dueAtMillis(view));
                (result == ConsumeResult.SUCCESS ? success : failure).record(Duration.ofMillis(delayMs));
            }
        };
    }

    /** 这条消息该被消费的时刻：定时消息取投递时间，其余取产生时间。 */
    static long dueAtMillis(MessageView view) {
        long born = view.getBornTimestamp();
        return view.getDeliveryTimestamp()
                .map(deliverAt -> Math.max(deliverAt, born))
                .orElse(born);
    }

    private Timer timer(String consumerGroup, String result) {
        return Timer.builder(DELAY_METRIC)
                .description("消息的端到端消费延迟：处理完的时刻减去产生时间（定时消息减去定好的投递时间）")
                .tag("group", consumerGroup)
                .tag("result", result)
                .register(registry);
    }
}
