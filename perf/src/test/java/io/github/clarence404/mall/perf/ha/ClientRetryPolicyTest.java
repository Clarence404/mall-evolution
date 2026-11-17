package io.github.clarence404.mall.perf.ha;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 客户端重试策略：哪些失败重试、退避上限、Retry-After、重试预算。 */
class ClientRetryPolicyTest {

    @Test
    void onlyTransientFailuresAreRetried() {
        ClientRetryPolicy p = immediate(3);

        assertThat(p.shouldRetry(0, 0)).isTrue(); // 超时 / 连接失败
        assertThat(p.shouldRetry(503, 0)).isTrue();
        assertThat(p.shouldRetry(429, 0)).isTrue();
        assertThat(p.shouldRetry(409, 0)).isFalse(); // 业务结果，重试也没用
        assertThat(p.shouldRetry(201, 0)).isFalse();
        assertThat(p.shouldRetry(503, 3)).isFalse(); // 次数用完
    }

    @Test
    void noneNeverRetries() {
        ClientRetryPolicy p = new ClientRetryPolicy(
                ClientRetryPolicy.Mode.NONE, 3, Duration.ZERO, Duration.ZERO, false, RetryBudget.unlimited());

        assertThat(p.shouldRetry(503, 0)).isFalse();
    }

    @Test
    void immediateDoesNotWait() {
        assertThat(immediate(3).backoff(2, "5")).isZero();
    }

    @Test
    void backoffIsCappedJitteredAndHonorsRetryAfter() {
        ClientRetryPolicy p = new ClientRetryPolicy(
                ClientRetryPolicy.Mode.BACKOFF,
                5,
                Duration.ofMillis(200),
                Duration.ofMillis(1000),
                true,
                RetryBudget.unlimited());

        for (int i = 0; i < 200; i++) {
            assertThat(p.backoff(0, null).toMillis()).isBetween(0L, 200L); // 第一次：[0, 200]
            assertThat(p.backoff(4, null).toMillis()).isBetween(0L, 1000L); // 200×16 被封顶到 1000
        }
        assertThat(p.backoff(0, "2").toMillis()).isGreaterThanOrEqualTo(2000); // 服务端说等 2 秒
        assertThat(p.backoff(0, "Wed, 21 Oct 2015 07:28:00 GMT").toMillis()).isBetween(0L, 200L);
    }

    @Test
    void budgetCapsRetriesToARatioOfFirstAttempts() {
        RetryBudget budget = new RetryBudget(0.1, 2);
        ClientRetryPolicy p = new ClientRetryPolicy(
                ClientRetryPolicy.Mode.BACKOFF, 3, Duration.ofMillis(1), Duration.ofMillis(1), false, budget);

        // 初始 2 个令牌用完后，每 10 个首次请求才攒出 1 次重试
        assertThat(p.shouldRetry(503, 0)).isTrue();
        assertThat(p.shouldRetry(503, 0)).isTrue();
        assertThat(p.shouldRetry(503, 0)).isFalse();
        for (int i = 0; i < 10; i++) {
            budget.onFirstAttempt();
        }
        assertThat(p.shouldRetry(503, 0)).isTrue();
        assertThat(p.shouldRetry(503, 0)).isFalse();
        assertThat(budget.summary()).contains("retriesGranted=3").contains("retriesDenied=2");
    }

    @Test
    void unlimitedBudgetAlwaysGrants() {
        RetryBudget budget = RetryBudget.unlimited();

        for (int i = 0; i < 100; i++) {
            assertThat(budget.tryRetry()).isTrue();
        }
    }

    private static ClientRetryPolicy immediate(int max) {
        return new ClientRetryPolicy(
                ClientRetryPolicy.Mode.IMMEDIATE, max, Duration.ZERO, Duration.ZERO, false, RetryBudget.unlimited());
    }
}
