package io.github.clarence404.mall.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IdConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(IdConfig.class);

    @Test
    void startupFailsWhenWorkerIdMissing() {
        runner.run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("mall.id.worker-id"));
    }

    @Test
    void startupFailsWhenWorkerIdBlank() {
        runner.withPropertyValues("mall.id.worker-id=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startupFailsWhenWorkerIdOutOfRange() {
        runner.withPropertyValues("mall.id.worker-id=1024")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void createsGeneratorWhenWorkerIdConfigured() {
        runner.withPropertyValues("mall.id.worker-id=2")
                .run(context -> assertThat(context).hasSingleBean(IdGenerator.class));
    }
}
