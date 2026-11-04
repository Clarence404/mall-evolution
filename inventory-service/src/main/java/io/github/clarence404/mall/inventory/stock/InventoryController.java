package io.github.clarence404.mall.inventory.stock;

import io.github.clarence404.mall.inventory.api.ConfirmStockRequest;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.ReleaseStockRequest;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.api.ReserveStockRequest;
import io.github.clarence404.mall.inventory.api.StockView;
import io.github.clarence404.mall.inventory.reserve.ReservationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/** Controller 直接实现 InventoryApi，路径与参数注解从接口继承。 */
@RestController
@RequiredArgsConstructor
class InventoryController implements InventoryApi {

    private final InventoryService inventoryService;
    private final ReservationService reservationService;

    @Override
    public List<StockView> listStocks(List<Long> skuIds) {
        return inventoryService.listStocks(skuIds);
    }

    @Override
    public ReserveResult reserve(ReserveStockRequest req) {
        return reservationService.reserve(req.orderNo(), req.lines());
    }

    @Override
    public void release(ReleaseStockRequest req) {
        reservationService.release(req.orderNo(), req.lines());
    }

    @Override
    public void confirm(ConfirmStockRequest req) {
        reservationService.confirm(req.orderNo());
    }
}
