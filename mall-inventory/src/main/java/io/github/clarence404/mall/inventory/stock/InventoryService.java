package io.github.clarence404.mall.inventory.stock;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.StockView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 阶段一：订单与库存在同一个库，扣减加入下单的本地事务（MANDATORY），由调用方保证按 sku_id 升序。
 */
@Service
@RequiredArgsConstructor
public class InventoryService implements InventoryApi {

    private final InventoryMapper inventoryMapper;

    @Override
    public List<StockView> listStocks(List<Long> skuIds) {
        return skuIds.isEmpty() ? List.of() : inventoryMapper.selectStocks(skuIds);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deduct(long skuId, int quantity) {
        return inventoryMapper.deduct(skuId, quantity) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(long skuId, int quantity) {
        inventoryMapper.restore(skuId, quantity);
    }
}
