package io.github.clarence404.mall.common.demo.fault;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 仅演示环境：通用的故障注入接口，三个服务（mall-app、inventory-service、payment-service）都有，给高可用实验脚本用
 * （见仓库根目录 experiments/）。只在 demo profile 下注册，路径在 /internal/** 下，网关不路由，只能在本机或集群内调。
 *
 * <pre>
 * PUT    /internal/demo/faults/{name}   新增或更新一条规则（同名、并发上限不变时原地更新），请求体见 {@link DemoFaultSpec}
 * GET    /internal/demo/faults          列出生效中的规则（含排队数、在途数）
 * DELETE /internal/demo/faults/{name}   撤销一条
 * DELETE /internal/demo/faults          全部撤销
 * </pre>
 *
 * <p>库存服务原有的 {@code /internal/demo/fault}（单数，只能给 reserve 加延迟，类名 DemoFaultController）是演示控制台用的，保持不变；
 * 这个类特意不叫 DemoFaultController，免得两个同名 Bean 冲突。
 */
@RestController
@Profile("demo")
@RequestMapping("/internal/demo/faults")
class DemoFaultRulesController {

    private final DemoFaultRegistry registry;

    DemoFaultRulesController(DemoFaultRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    List<DemoFaultView> list() {
        return registry.list();
    }

    @PutMapping("/{name}")
    DemoFaultView put(@PathVariable String name, @RequestBody DemoFaultSpec spec) {
        try {
            return registry.put(name, spec);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @DeleteMapping("/{name}")
    ResponseEntity<Void> remove(@PathVariable String name) {
        registry.remove(name);
        return ResponseEntity.noContent().build(); // 幂等：不存在也返回 204
    }

    @DeleteMapping
    ResponseEntity<Void> clear() {
        registry.clear();
        return ResponseEntity.noContent().build();
    }
}
