package io.github.clarence404.mall.common.demo.fault;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 仅演示环境：当前生效的故障规则。只存在内存里，重启即全部消失；每条规则都有有效期，到期自动失效。
 *
 * <p>多条规则同时命中一个请求时，按规则名的字典序取第一条（只生效一条，避免叠加出意料之外的效果）。
 */
@Slf4j
@Component
@Profile("demo")
public class DemoFaultRegistry {

    private final Map<String, DemoFault> faults = new ConcurrentSkipListMap<>();
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public DemoFaultRegistry(MeterRegistry meterRegistry) {
        this(meterRegistry, Clock.systemUTC());
    }

    /** 测试用：传入可拨动的时钟，验证到期失效。 */
    DemoFaultRegistry(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * 新增或更新同名规则。
     *
     * <p>同名且并发上限不变时原地更新：信号量、排队中的请求、指标都沿用，只换延迟、失败比例等参数——实验里「下游突然变慢 20 秒再恢复」
     * 就是对同一条规则 PUT 两次，排队的积压不会因为换规则而凭空消失。并发上限变了才整条替换（旧规则上排队的请求按旧规则走完）。
     */
    public DemoFaultView put(String name, DemoFaultSpec spec) {
        DemoFaultRule rule = DemoFaultRule.of(name, spec, clock.instant());
        DemoFault existing = faults.get(name);
        if (existing != null && existing.rule().maxConcurrent() == rule.maxConcurrent()) {
            existing.update(rule);
            log.warn("演示故障已更新：{}", rule);
            return DemoFaultView.of(existing);
        }
        DemoFault previous = faults.remove(name);
        if (previous != null) {
            previous.unregister(meterRegistry);
        }
        DemoFault fault = new DemoFault(rule, meterRegistry);
        faults.put(name, fault);
        log.warn("演示故障已注入：{}", rule);
        return DemoFaultView.of(fault);
    }

    public boolean remove(String name) {
        DemoFault removed = faults.remove(name);
        if (removed == null) {
            return false;
        }
        removed.unregister(meterRegistry);
        log.warn("演示故障已撤销：{}", name);
        return true;
    }

    public void clear() {
        List.copyOf(faults.keySet()).forEach(this::remove);
    }

    public List<DemoFaultView> list() {
        purgeExpired();
        return faults.values().stream().map(DemoFaultView::of).toList();
    }

    /** 找命中这个请求的规则；没有返回 null。顺手清掉已过期的规则。 */
    DemoFault match(String method, String path) {
        if (faults.isEmpty()) {
            return null;
        }
        Instant now = clock.instant();
        for (DemoFault fault : faults.values()) {
            if (fault.rule().expired(now)) {
                remove(fault.rule().name());
                continue;
            }
            if (fault.rule().matches(method, path)) {
                return fault;
            }
        }
        return null;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        faults.values().stream()
                .filter(f -> f.rule().expired(now))
                .map(f -> f.rule().name())
                .toList()
                .forEach(this::remove);
    }
}
