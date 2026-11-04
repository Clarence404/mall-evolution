package io.github.clarence404.mall.inventory.api;

import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 库存服务的远程契约：服务端 Controller 直接实现它，mall-app 用同一个接口生成客户端，契约不会两边写漂。
 *
 * <p>只加字段、不改含义；{@code /internal/**} 只在集群内可达，网关不路由它。
 */
@HttpExchange("/internal/inventory")
public interface InventoryApi {

    @GetExchange("/stocks")
    List<StockView> listStocks(@RequestParam List<Long> skuIds);

    @PostExchange("/deduct")
    void deduct(@RequestBody DeductStockRequest req); // 按 sku_id 升序条件更新，和阶段一同一条 SQL

    @PostExchange("/restore")
    void restore(@RequestBody RestoreStockRequest req);
}
