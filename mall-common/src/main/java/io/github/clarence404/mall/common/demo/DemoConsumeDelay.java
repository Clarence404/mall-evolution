package io.github.clarence404.mall.common.demo;

import io.github.clarence404.mall.common.mq.ConsumeHold;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 仅演示环境：处理每条消息之前多停留 mall.demo.consume-delay-ms 毫秒，默认 0 即不停。
 * mall.demo.consume-delay-groups 是逗号分隔的消费组名，只对这些组生效；留空表示本服务的全部消费组。
 *
 * <p>这是一个模型，不是真实耗时：演示环境里消费一条消息只有几毫秒到十几毫秒，消费能力远大于压得出来的下单量，看不到堆积。
 * 给它一个明确的值（比如 100ms），一个消费组的处理能力就约等于「消费线程数 × 10 条/秒」，突发流量超过它时消息在 Broker 里排队，
 * 消费延迟随之上涨。停留发生在业务处理之前，不占数据库连接。
 */
@Slf4j
@Component
@Profile("demo")
class DemoConsumeDelay implements ConsumeHold {

    private final long delayMs;
    private final Set<String> groups;

    DemoConsumeDelay(
            @Value("${mall.demo.consume-delay-ms:0}") long delayMs,
            @Value("${mall.demo.consume-delay-groups:}") String groups) {
        this.delayMs = delayMs;
        this.groups = Arrays.stream(groups.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (delayMs > 0) {
            log.warn("消息消费按演示开关运行：每条消息处理前停留 {}ms，生效的消费组：{}", delayMs, this.groups.isEmpty() ? "全部" : this.groups);
        }
    }

    @Override
    public void beforeConsume(String consumerGroup) {
        if (delayMs <= 0 || (!groups.isEmpty() && !groups.contains(consumerGroup))) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
