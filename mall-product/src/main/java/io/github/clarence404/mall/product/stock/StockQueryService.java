package io.github.clarence404.mall.product.stock;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.StockView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

/** 只读查询只在这一层重试：最多 1 次，随机退避 20–80ms（Spring Framework 7 自带注解）。写接口一律不重试。 */
@Service
@RequiredArgsConstructor
public class StockQueryService {

    private final InventoryApi inventoryApi;

    @Retryable(includes = ResourceAccessException.class, maxRetries = 1, delay = 20, jitter = 60)
    public List<StockView> listStocks(List<Long> skuIds) {
        return inventoryApi.listStocks(skuIds); // 失败时商品详情改用 Redis 里 10 秒 TTL 的「有货」提示
    }
}
