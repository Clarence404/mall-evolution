package io.github.clarence404.mall.inventory.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 仅演示环境：当前注入的故障。只存在内存里，重启即恢复。 */
@Component
@Profile("demo")
class DemoFaults {

    private volatile int reserveDelayMs;

    int getReserveDelayMs() {
        return reserveDelayMs;
    }

    void setReserveDelayMs(int reserveDelayMs) {
        this.reserveDelayMs = reserveDelayMs;
    }
}
