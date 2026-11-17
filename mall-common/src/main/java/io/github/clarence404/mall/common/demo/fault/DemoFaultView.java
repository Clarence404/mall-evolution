package io.github.clarence404.mall.common.demo.fault;

import java.time.Instant;

/**
 * 仅演示环境：故障规则的当前状态，接口返回给实验脚本，脚本把它写进实验记录。
 *
 * @param waiting 正在排队等并发许可的请求数
 * @param inflight 正在处理的请求数
 */
public record DemoFaultView(
        String name,
        String method,
        String path,
        int delayMs,
        int failPercent,
        int failStatus,
        int maxConcurrent,
        int responseDelayMs,
        int delayPercent,
        Instant expiresAt,
        int waiting,
        int inflight) {

    static DemoFaultView of(DemoFault fault) {
        DemoFaultRule r = fault.rule();
        return new DemoFaultView(
                r.name(),
                r.method(),
                r.pathPattern(),
                r.delayMs(),
                r.failPercent(),
                r.failStatus(),
                r.maxConcurrent(),
                r.responseDelayMs(),
                r.delayPercent(),
                r.expiresAt(),
                (int) fault.waiting(),
                fault.inflight());
    }
}
