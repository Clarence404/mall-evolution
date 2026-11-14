package io.github.clarence404.mall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** prod 与 demo 互斥：同时激活时容器创建失败并给出明确原因；各自单独激活不受影响。 */
class ProdDemoProfileGuardTest {

    ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ProdDemoProfileGuard.class);

    @Test
    void startupFailsWithClearMessageWhenProdAndDemoAreBothActive() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod", "demo"))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("prod 与 demo 两个 profile 不能同时激活");
                });
    }

    @Test
    void orderOfProfilesDoesNotMatter() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("demo", "local", "prod"))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void eitherProfileAloneStartsNormally() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev", "local", "demo"))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
