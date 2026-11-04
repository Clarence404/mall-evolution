package io.github.clarence404.mall.inventory.stock;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.inventory.api.InventoryErrorCode;
import io.github.clarence404.mall.inventory.api.StockLine;
import io.github.clarence404.mall.inventory.api.StockView;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 库存服务内部实现：和阶段一同一条 stock >= n 的条件更新，按 sku_id 升序避免死锁。 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryMapper inventoryMapper;

    public List<StockView> listStocks(List<Long> skuIds) {
        return skuIds.isEmpty() ? List.of() : inventoryMapper.selectStocks(skuIds);
    }

    /** 任一 SKU 不够就整体回滚，返回 409 STOCK_INSUFFICIENT。 */
    @Transactional(rollbackFor = Exception.class)
    public void deduct(String orderNo, List<StockLine> lines) {
        lines.stream().sorted(Comparator.comparing(StockLine::skuId)).forEach(l -> {
            if (inventoryMapper.deduct(l.skuId(), l.quantity()) == 0) {
                throw new BizException(
                        InventoryErrorCode.STOCK_INSUFFICIENT, "SKU " + l.skuId() + " 库存不足，订单 " + orderNo);
            }
        });
    }

    /** 按 order_no 幂等：调用方超时重试、两台实例同时重试，库存只加一次。 */
    @Transactional(rollbackFor = Exception.class)
    public void restore(String orderNo, List<StockLine> lines) {
        try {
            inventoryMapper.insertRestoreLog(orderNo);
        } catch (DuplicateKeyException e) {
            return; // 已经归还过
        }
        lines.stream()
                .sorted(Comparator.comparing(StockLine::skuId))
                .forEach(l -> inventoryMapper.restore(l.skuId(), l.quantity()));
    }
}
