package io.github.clarence404.mall.inventory.demo;

import io.github.clarence404.mall.inventory.reserve.ReserveHold;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 仅演示环境：预占扣完库存后，在事务里（行锁还没放）多停留 mall.demo.reserve-hold-ms 毫秒，默认 0 即不停。
 *
 * <p>这是一个模型，不是真实耗时：生产上一次预占持锁的时间由同一事务里的其他语句、提交落盘和网络往返决定，
 * 开发机上这些都很短，压不到单行的拐点。给它一个明确的值（比如 10ms），单行的容量就约等于 1000 / 10 = 100 单/秒，
 * 分 n 个桶约为 n 倍，拐点不再取决于这台机器的磁盘和 CPU。
 */
@Component
@Profile("demo")
class DemoReserveHold implements ReserveHold {

    private final long holdMs;

    DemoReserveHold(@Value("${mall.demo.reserve-hold-ms:0}") long holdMs) {
        this.holdMs = holdMs;
    }

    @Override
    public void afterDeduct() {
        if (holdMs <= 0) {
            return;
        }
        try {
            Thread.sleep(holdMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
