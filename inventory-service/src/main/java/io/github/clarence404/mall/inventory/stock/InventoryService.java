package io.github.clarence404.mall.inventory.stock;

import io.github.clarence404.mall.inventory.api.StockView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 库存查询（商品页展示有货无货）。扣减走 {@link io.github.clarence404.mall.inventory.reserve.ReservationService} 的预占。 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryMapper inventoryMapper;

    public List<StockView> listStocks(List<Long> skuIds) {
        return skuIds.isEmpty() ? List.of() : inventoryMapper.selectStocks(skuIds);
    }
}
