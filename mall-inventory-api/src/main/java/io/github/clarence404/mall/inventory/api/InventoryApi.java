package io.github.clarence404.mall.inventory.api;

import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 库存服务的远程契约：服务端 Controller 直接实现它，mall-app 用同一个接口生成客户端。
 *
 * <p>阶段三的一致性改造：deduct / restore 换成 TCC 风格的预占 reserve / release / confirm。三个接口都按
 * (order_no, sku_id) 幂等；订单服务自己就是协调者，订单状态就是全局事务状态。
 */
@HttpExchange("/internal/inventory")
public interface InventoryApi {

    @GetExchange("/stocks")
    List<StockView> listStocks(@RequestParam List<Long> skuIds);

    /** 预占：stock -n, reserved +n；遇到 release 先到留下的占位返回 REJECTED（防悬挂）。 */
    @PostExchange("/reserve")
    ReserveResult reserve(@RequestBody ReserveStockRequest req);

    /** 释放：没预占过就写一行 RELEASED 占位（空回滚）；已预占则 stock +n, reserved -n。 */
    @PostExchange("/release")
    void release(@RequestBody ReleaseStockRequest req);

    /** 确认：RESERVED → CONFIRMED，reserved -n。 */
    @PostExchange("/confirm")
    void confirm(@RequestBody ConfirmStockRequest req);
}
