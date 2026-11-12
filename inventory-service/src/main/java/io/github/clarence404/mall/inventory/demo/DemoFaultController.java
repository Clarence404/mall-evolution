package io.github.clarence404.mall.inventory.demo;

import io.github.clarence404.mall.inventory.api.StockView;
import io.github.clarence404.mall.inventory.stock.InventoryMapper;
import io.github.clarence404.mall.inventory.stock.InventoryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅演示环境：给演示控制台用的故障注入开关和库存查看，以及给流量模拟器（simulator 模块）用的补货。只在 demo profile 下注册，生产没有这些接口。
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

    /** 补货上限：模拟器默认补到 1000，留足余量但防止误填出离谱的数。 */
    static final int MAX_RESTOCK = 100_000;

    private final DemoFaults faults;
    private final InventoryService inventoryService;
    private final InventoryMapper inventoryMapper;

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

    /**
     * 补货：可售数量低于 target 时补到 target（只补不减，分桶 SKU 不处理），返回补货后的库存。流量模拟器长期运行时，已支付的订单会一直消耗库存，
     * 低于水位就调这个接口补回去，免得几天后全部「库存不足」。
     */
    @PutMapping("/stocks/{skuId}/restock")
    List<StockView> restock(@PathVariable long skuId, @RequestParam(defaultValue = "1000") int target) {
        inventoryMapper.restockTo(skuId, Math.clamp(target, 0, MAX_RESTOCK));
        return inventoryService.listStocks(List.of(skuId));
    }

    private FaultView view() {
        return new FaultView(faults.getReserveDelayMs());
    }

    record FaultView(int reserveDelayMs) {}
}
