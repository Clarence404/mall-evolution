package io.github.clarence404.mall.inventory.demo;

import io.github.clarence404.mall.inventory.api.StockView;
import io.github.clarence404.mall.inventory.stock.InventoryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅演示环境：给演示控制台用的故障注入开关和库存查看。只在 demo profile 下注册，生产没有这些接口。
 *
 * <p>网关只在 demo profile 下把 {@code /api/demo/inventory/**} 转到这里的 {@code /internal/demo/**}。
 */
@RestController
@Profile("demo")
@RequestMapping("/internal/demo")
@RequiredArgsConstructor
class DemoFaultController {

    /** 上限 3 秒：网关的 response-timeout 是 3 秒，再长没有意义。 */
    static final int MAX_DELAY_MS = 3000;

    private final DemoFaults faults;
    private final InventoryService inventoryService;

    @GetMapping("/fault")
    FaultView get() {
        return view();
    }

    /** 给 reserve 注入固定延迟。默认 800ms，超过订单侧 500ms 的读超时。 */
    @PutMapping("/fault")
    FaultView inject(@RequestParam(defaultValue = "800") int reserveDelayMs) {
        faults.setReserveDelayMs(Math.clamp(reserveDelayMs, 0, MAX_DELAY_MS));
        return view();
    }

    @DeleteMapping("/fault")
    FaultView clear() {
        faults.setReserveDelayMs(0);
        return view();
    }

    @GetMapping("/stocks")
    List<StockView> stocks(@RequestParam List<Long> skuIds) {
        return inventoryService.listStocks(skuIds);
    }

    private FaultView view() {
        return new FaultView(faults.getReserveDelayMs());
    }

    record FaultView(int reserveDelayMs) {}
}
