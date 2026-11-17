package io.github.clarence404.mall.common.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 模拟 SIGTERM：先回 202，再在另一个线程里以退出码 0 正常退出（这里替换掉真正的 System.exit）。 */
class DemoLifecycleControllerTest {

    @Test
    void respondsFirstThenExitsWithZero() throws Exception {
        CompletableFuture<Integer> exited = new CompletableFuture<>();
        DemoLifecycleController controller = new DemoLifecycleController(exited::complete);

        var res = controller.exit(50);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody()).containsEntry("exitInMs", 50);
        assertThat(exited.get(5, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void delayIsCapped() {
        DemoLifecycleController controller = new DemoLifecycleController(code -> {});

        assertThat(controller.exit(60_000).getBody()).containsEntry("exitInMs", 5_000);
        assertThat(controller.exit(-1).getBody()).containsEntry("exitInMs", 0);
    }
}
