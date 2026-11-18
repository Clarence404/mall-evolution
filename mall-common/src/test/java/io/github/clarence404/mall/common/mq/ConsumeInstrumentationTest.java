package io.github.clarence404.mall.common.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

/**
 * 消费延迟指标 mq.consume.delay：处理完的时刻减去消息该被消费的时刻（普通消息是产生时间，定时消息是定好的投递时间），
 * 按消费组与处理结果分开记；演示环境的回调点在业务处理之前执行，它花的时间也算进延迟。
 */
class ConsumeInstrumentationTest {

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicLong now = new AtomicLong(1_000_000);
    ConsumeInstrumentation instrumentation = new ConsumeInstrumentation(registry, now::get);

    @Test
    void recordsFinishTimeMinusBornTimestampPerGroup() {
        Function<MessageView, ConsumeResult> wrapped = instrumentation.wrap("inventory-release-consumer", view -> {
            now.addAndGet(30); // 处理花了 30ms
            return ConsumeResult.SUCCESS;
        });

        assertThat(wrapped.apply(message(now.get() - 120, null))).isEqualTo(ConsumeResult.SUCCESS);

        Timer timer = timer("inventory-release-consumer", "success");
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(150); // 排队 120ms + 处理 30ms
        assertThat(timer("inventory-release-consumer", "failure").count()).isZero();
    }

    @Test
    void delayedMessageIsMeasuredFromItsDeliveryTime() {
        Function<MessageView, ConsumeResult> wrapped =
                instrumentation.wrap("order-timeout-consumer", view -> ConsumeResult.SUCCESS);

        // 15 分钟前发出的定时消息，定在 2 秒前投递：延迟是 2 秒，而不是 15 分钟
        wrapped.apply(message(now.get() - 900_000, now.get() - 2_000));

        assertThat(timer("order-timeout-consumer", "success").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(2_000);
    }

    @Test
    void failureResultAndExceptionsAreRecordedAsFailure() {
        Function<MessageView, ConsumeResult> failing =
                instrumentation.wrap("points-consumer", view -> ConsumeResult.FAILURE);
        Function<MessageView, ConsumeResult> throwing = instrumentation.wrap("notify-consumer", view -> {
            throw new IllegalStateException("消息体读不出来");
        });

        assertThat(failing.apply(message(now.get() - 10, null))).isEqualTo(ConsumeResult.FAILURE);
        assertThatThrownBy(() -> throwing.apply(message(now.get() - 10, null)))
                .isInstanceOf(IllegalStateException.class); // 异常原样抛给客户端，由它按失败处理

        assertThat(timer("points-consumer", "failure").count()).isEqualTo(1);
        assertThat(timer("points-consumer", "success").count()).isZero();
        assertThat(timer("notify-consumer", "failure").count()).isEqualTo(1);
    }

    @Test
    void clockSkewNeverRecordsNegativeDelay() {
        Function<MessageView, ConsumeResult> wrapped =
                instrumentation.wrap("shipment-consumer", view -> ConsumeResult.SUCCESS);

        wrapped.apply(message(now.get() + 5_000, null)); // 生产者的时钟比本机快 5 秒

        Timer timer = timer("shipment-consumer", "success");
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isZero();
    }

    @Test
    void timersExistBeforeTheFirstMessage() {
        instrumentation.wrap("order-payment-consumer", view -> ConsumeResult.SUCCESS);

        assertThat(timer("order-payment-consumer", "success").count()).isZero();
        assertThat(timer("order-payment-consumer", "failure").count()).isZero();
    }

    @Test
    void holdRunsBeforeTheListenerAndCountsTowardsTheDelay() {
        List<String> calls = new ArrayList<>();
        instrumentation.setHold(group -> {
            calls.add("hold:" + group);
            now.addAndGet(100); // 演示环境的停留
        });
        Function<MessageView, ConsumeResult> wrapped = instrumentation.wrap("inventory-release-consumer", view -> {
            calls.add("listener");
            return ConsumeResult.SUCCESS;
        });

        wrapped.apply(message(now.get(), null));

        assertThat(calls).containsExactly("hold:inventory-release-consumer", "listener");
        assertThat(timer("inventory-release-consumer", "success").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(100);
    }

    @Test
    void withoutHoldNothingRunsBeforeTheListener() {
        // 正式环境没有 ConsumeHold 的实现：默认的 NONE 是空操作
        ConsumeHold.NONE.beforeConsume("any-group");
        Function<MessageView, ConsumeResult> wrapped =
                instrumentation.wrap("points-consumer", view -> ConsumeResult.SUCCESS);

        wrapped.apply(message(now.get() - 7, null));

        assertThat(timer("points-consumer", "success").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(7);
    }

    private Timer timer(String group, String result) {
        return registry.get(ConsumeInstrumentation.DELAY_METRIC)
                .tag("group", group)
                .tag("result", result)
                .timer();
    }

    private static MessageView message(long bornAt, Long deliverAt) {
        MessageView view = mock(MessageView.class);
        when(view.getBornTimestamp()).thenReturn(bornAt);
        when(view.getDeliveryTimestamp()).thenReturn(Optional.ofNullable(deliverAt));
        return view;
    }
}
