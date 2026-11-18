package io.github.clarence404.mall.product.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * 演示开关的实现：原样返回配置的值；load-delay-ms 大于 0 时回源被包在一个事务里（先取连接、停留、再查库、提交），
 * 等于 0 时和正式环境一样直接查，不开事务。
 */
class DemoDetailCachePolicyTest {

    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    @Test
    void returnsConfiguredSwitches() {
        DemoDetailCachePolicy p =
                new DemoDetailCachePolicy(false, true, false, true, 0, (PlatformTransactionManager) null);

        assertThat(p.l1Enabled()).isFalse();
        assertThat(p.l2Enabled()).isTrue();
        assertThat(p.singleFlightEnabled()).isFalse();
        assertThat(p.nullCacheEnabled()).isTrue();
    }

    @Test
    void zeroDelayLoadsDirectlyWithoutTransaction() {
        DemoDetailCachePolicy p = new DemoDetailCachePolicy(true, true, true, true, 0, txManager);

        assertThat(p.aroundDbLoad(() -> "行")).isEqualTo("行");

        verify(txManager, never()).getTransaction(any());
    }

    @Test
    void positiveDelayHoldsATransactionWhileSleepingThenLoads() {
        TransactionStatus status = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(status);
        DemoDetailCachePolicy p = new DemoDetailCachePolicy(true, true, true, true, 80, txManager);
        List<String> calls = new ArrayList<>();

        long t0 = System.nanoTime();
        String row = p.aroundDbLoad(() -> {
            calls.add("load");
            // 查库发生在事务已经开始（连接已取到）、还没提交的时候
            verify(txManager).getTransaction(any());
            verify(txManager, never()).commit(any());
            return "行";
        });

        assertThat(row).isEqualTo("行");
        assertThat(calls).containsExactly("load");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(70));
        InOrder order = inOrder(txManager);
        order.verify(txManager).getTransaction(any());
        order.verify(txManager).commit(status);
    }

    @Test
    void positiveDelayWithoutTransactionManagerJustSleeps() {
        DemoDetailCachePolicy p =
                new DemoDetailCachePolicy(true, true, true, true, 80, (PlatformTransactionManager) null);

        long t0 = System.nanoTime();
        assertThat(p.aroundDbLoad(() -> "行")).isEqualTo("行");

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(70));
    }

    @Test
    void interruptedSleepKeepsTheInterruptFlagAndStillLoads() {
        DemoDetailCachePolicy p =
                new DemoDetailCachePolicy(true, true, true, true, 5_000, (PlatformTransactionManager) null);

        Thread.currentThread().interrupt();
        long t0 = System.nanoTime();
        assertThat(p.aroundDbLoad(() -> "行")).isEqualTo("行");

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
        assertThat(Thread.interrupted()).isTrue(); // 顺便清掉标志，不影响后面的测试
    }
}
