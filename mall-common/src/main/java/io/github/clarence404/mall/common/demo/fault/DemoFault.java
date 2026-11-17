package io.github.clarence404.mall.common.demo.fault;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仅演示环境：一条生效中的故障规则，连同它的并发许可和指标。
 *
 * <p>指标（实验时用它们看注入的故障到底起了多大作用，规则删除时一起注销）：
 *
 * <ul>
 *   <li>{@code demo_fault_requests_total{rule, outcome}}：命中规则的请求数，outcome 为 failed（被直接判失败）或 passed（放行处理）
 *   <li>{@code demo_fault_waiting{rule}}：正在排队等并发许可的请求数——maxConcurrent 模拟的瓶颈前积压了多少活
 *   <li>{@code demo_fault_inflight{rule}}：已拿到许可、正在处理的请求数
 * </ul>
 */
final class DemoFault {

    /** 同名、并发上限不变的 PUT 只换参数（如把延迟从 40ms 调到 400ms），许可、排队中的请求和指标都沿用。 */
    private volatile DemoFaultRule rule;

    private final Semaphore permits;
    private final AtomicInteger inflight = new AtomicInteger();
    private final Counter failed;
    private final Counter passed;
    private final List<Meter> meters;

    DemoFault(DemoFaultRule rule, MeterRegistry registry) {
        this.rule = rule;
        this.permits = rule.maxConcurrent() > 0 ? new Semaphore(rule.maxConcurrent(), true) : null;
        this.failed = Counter.builder("demo.fault.requests")
                .tag("rule", rule.name())
                .tag("outcome", "failed")
                .register(registry);
        this.passed = Counter.builder("demo.fault.requests")
                .tag("rule", rule.name())
                .tag("outcome", "passed")
                .register(registry);
        Gauge waiting = Gauge.builder("demo.fault.waiting", this, DemoFault::waiting)
                .tag("rule", rule.name())
                .strongReference(true)
                .register(registry);
        Gauge running = Gauge.builder("demo.fault.inflight", inflight, AtomicInteger::get)
                .tag("rule", rule.name())
                .strongReference(true)
                .register(registry);
        this.meters = List.of(failed, passed, waiting, running);
    }

    DemoFaultRule rule() {
        return rule;
    }

    /** 原地更新参数；只允许并发上限相同（换上限就要换信号量，那是另一条规则）。 */
    void update(DemoFaultRule newRule) {
        if (newRule.maxConcurrent() != rule.maxConcurrent()) {
            throw new IllegalArgumentException("并发上限不同，不能原地更新");
        }
        this.rule = newRule;
    }

    /** 拿并发许可（没有上限时直接返回）。排队不设超时：模拟的就是「请求在瓶颈前无限排队」。 */
    void enter() throws InterruptedException {
        if (permits != null) {
            permits.acquire();
        }
        inflight.incrementAndGet();
    }

    void exit() {
        inflight.decrementAndGet();
        if (permits != null) {
            permits.release();
        }
    }

    /** 这次请求是否按比例直接失败。 */
    boolean rollFailure() {
        return rule.failPercent() >= 100
                || (rule.failPercent() > 0 && ThreadLocalRandom.current().nextInt(100) < rule.failPercent());
    }

    /** 这次请求是否施加延迟（delayPercent 按比例抽样）。 */
    boolean rollDelay() {
        int p = rule.delayPercent();
        return p >= 100 || (p > 0 && ThreadLocalRandom.current().nextInt(100) < p);
    }

    void recordFailed() {
        failed.increment();
    }

    void recordPassed() {
        passed.increment();
    }

    double waiting() {
        return permits == null ? 0 : permits.getQueueLength();
    }

    int inflight() {
        return inflight.get();
    }

    void unregister(MeterRegistry registry) {
        meters.forEach(registry::remove);
    }
}
