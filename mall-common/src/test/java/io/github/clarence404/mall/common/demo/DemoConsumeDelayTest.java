package io.github.clarence404.mall.common.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 演示用的消费延迟：默认不停留；配置了毫秒数才停，可以只对点名的消费组生效；被中断时保留中断标志。 */
class DemoConsumeDelayTest {

    @Test
    void zeroDelayDoesNotSleep() {
        DemoConsumeDelay delay = new DemoConsumeDelay(0, "");

        Thread.currentThread().interrupt(); // 真去 sleep 的话会吃掉中断再补上；这里只要求立刻返回
        assertThat(elapsed(() -> delay.beforeConsume("points-consumer"))).isLessThan(Duration.ofSeconds(2));
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void emptyGroupListAppliesToEveryGroup() {
        DemoConsumeDelay delay = new DemoConsumeDelay(80, "  ");

        assertThat(elapsed(() -> delay.beforeConsume("points-consumer"))).isGreaterThanOrEqualTo(Duration.ofMillis(70));
        assertThat(elapsed(() -> delay.beforeConsume("notify-consumer"))).isGreaterThanOrEqualTo(Duration.ofMillis(70));
    }

    @Test
    void onlyListedGroupsAreDelayed() {
        DemoConsumeDelay delay = new DemoConsumeDelay(300, "inventory-release-consumer, order-timeout-consumer");

        assertThat(elapsed(() -> delay.beforeConsume("inventory-release-consumer")))
                .isGreaterThanOrEqualTo(Duration.ofMillis(280));
        assertThat(elapsed(() -> delay.beforeConsume("order-timeout-consumer")))
                .isGreaterThanOrEqualTo(Duration.ofMillis(280));
        // 没点名的组不停留：留足余量（机器再慢也到不了 280ms 的一半）
        assertThat(elapsed(() -> delay.beforeConsume("inventory-confirm-consumer")))
                .isLessThan(Duration.ofMillis(140));
    }

    @Test
    void interruptedSleepKeepsTheInterruptFlag() {
        DemoConsumeDelay delay = new DemoConsumeDelay(5_000, "");

        Thread.currentThread().interrupt();
        Duration took = elapsed(() -> delay.beforeConsume("points-consumer"));

        assertThat(took).isLessThan(Duration.ofSeconds(2));
        assertThat(Thread.interrupted()).isTrue(); // 顺便清掉标志，不影响后面的测试
    }

    private static Duration elapsed(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return Duration.ofNanos(System.nanoTime() - t0);
    }
}
