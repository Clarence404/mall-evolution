package io.github.clarence404.mall.common.demo;

import java.util.Map;
import java.util.function.IntConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅演示环境：让本进程「收到 SIGTERM」——滚动发布实验（experiments/c-rolling-restart）用它在 Windows 上逐个停实例。
 *
 * <p>Linux 上 {@code kill <pid>} 发的 SIGTERM 会让 JVM 正常退出、执行关闭钩子：Spring Boot 在钩子里关闭容器，
 * 按 server.shutdown 的设置（graceful / immediate）处理在途请求，再销毁 Bean。Windows 没有 SIGTERM，
 * {@code Stop-Process} 等同 {@code kill -9}，钩子不会执行，测不出优雅停机的差别。这个接口先把 202 响应发出去，
 * 再在另一个线程里调 {@code System.exit(0)}，走的就是同一条关闭钩子的路径。
 *
 * <p>只在 demo profile 下注册，路径在 /internal/** 下，网关不路由。
 */
@Slf4j
@RestController
@Profile("demo")
class DemoLifecycleController {

    /** 默认等 200ms 再退出：让这次调用的响应先回到调用方。 */
    static final int DEFAULT_DELAY_MS = 200;

    private final IntConsumer exiter;

    DemoLifecycleController() {
        this(System::exit);
    }

    /** 测试用：替换掉真正的 System.exit。 */
    DemoLifecycleController(IntConsumer exiter) {
        this.exiter = exiter;
    }

    @PostMapping("/internal/demo/lifecycle/exit")
    ResponseEntity<Map<String, Integer>> exit(@RequestParam(defaultValue = "" + DEFAULT_DELAY_MS) int delayMs) {
        int delay = Math.clamp(delayMs, 0, 5_000);
        Thread t = new Thread(
                () -> {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("演示：模拟 SIGTERM，进程即将正常退出（执行关闭钩子）");
                    exiter.accept(0);
                },
                "demo-exit");
        t.setDaemon(false);
        t.start();
        return ResponseEntity.accepted().body(Map.of("exitInMs", delay));
    }
}
