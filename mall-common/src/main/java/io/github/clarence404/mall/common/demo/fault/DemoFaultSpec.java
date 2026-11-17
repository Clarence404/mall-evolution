package io.github.clarence404.mall.common.demo.fault;

/**
 * 仅演示环境：一条故障规则的请求体（{@code PUT /internal/demo/faults/{name}}）。除 path 外都可以不填，不填就是「不注入这一项」。
 *
 * <p>几项可以叠加，执行顺序：先占并发许可（maxConcurrent）→ 处理前延迟（delayMs）→ 按比例直接失败（failPercent）→
 * 正常处理 → 处理后延迟（responseDelayMs）→ 释放许可。
 *
 * @param method HTTP 方法，如 GET、POST；不填表示任意方法
 * @param path 路径模式（Spring PathPattern），如 {@code /internal/payments/*}、{@code /internal/inventory/reserve}
 * @param delayMs 进入业务处理前先等这么久，模拟「下游变慢」；上限 30 秒
 * @param failPercent 0–100：按这个比例直接返回 failStatus，不进业务处理，模拟「部分请求失败」
 * @param failStatus 失败时的状态码，默认 503，只允许 4xx / 5xx
 * @param maxConcurrent 大于 0 时，命中这条规则的请求同时最多处理这么多个，其余的排队等（公平、不设超时）。
 *     与 delayMs 一起用就是一个容量有限的瓶颈：容量 ≈ maxConcurrent / (delayMs + 实际处理时间)。排队的请求即使调用方已经超时放弃，
 *     轮到它时仍会被处理——这正是真实系统里「白干的活」，重试风暴实验靠它复现
 * @param responseDelayMs 业务已经处理完、响应发出前再等这么久，模拟「服务端成功了，但响应迟迟回不来」（调用方超时后重试，幂等实验用）；上限 30 秒
 * @param delayPercent 0–100，默认 100：两种延迟只对这个比例的请求生效（比如「三成下单响应迟到」），其余请求不延迟
 * @param ttlSeconds 规则多久后自动失效，默认 600 秒、最长 3600 秒：实验脚本中途出错时故障也会自己消失，不会一直挂着
 */
public record DemoFaultSpec(
        String method,
        String path,
        Integer delayMs,
        Integer failPercent,
        Integer failStatus,
        Integer maxConcurrent,
        Integer responseDelayMs,
        Integer delayPercent,
        Integer ttlSeconds) {}
