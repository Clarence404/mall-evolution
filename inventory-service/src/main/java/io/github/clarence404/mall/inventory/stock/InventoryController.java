package io.github.clarence404.mall.inventory.stock;

import io.github.clarence404.mall.inventory.api.DeductStockRequest;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.RestoreStockRequest;
import io.github.clarence404.mall.inventory.api.StockView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/** Controller 直接实现 InventoryApi，路径与参数注解从接口继承。 */
@RestController
@RequiredArgsConstructor
class InventoryController implements InventoryApi {

    private final InventoryService inventoryService;

    @Override
    public List<StockView> listStocks(List<Long> skuIds) {
        return inventoryService.listStocks(skuIds);
    }

    @Override
    public void deduct(DeductStockRequest req) {
        inventoryService.deduct(req.orderNo(), req.lines());
    }

    @Override
    public void restore(RestoreStockRequest req) {
        inventoryService.restore(req.orderNo(), req.lines());
    }
}
