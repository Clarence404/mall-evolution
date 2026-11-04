package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.inventory.api.DeductStockRequest;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.RestoreStockRequest;
import io.github.clarence404.mall.inventory.api.StockLine;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 下单应用服务（拆分过渡期）：先远程扣库存，再在本地事务里写单，写单失败把库存还回去。
 *
 * <p>三个已知的洞：deduct 超时不知道扣没扣上；restore 本身可能失败；进程在两步之间崩溃。过渡期不自动重试写接口，
 * 靠每日的库存与订单核对脚本人工处理，彻底的解法是下一篇的库存预占。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderService {

    private final OrderMapper orderMapper;
    private final PricingService pricingService;
    private final PlaceOrderTxService placeOrderTxService;
    private final OrderNoGenerator orderNoGenerator;
    private final InventoryApi inventoryApi;

    public PlaceOrderResult place(long userId, String idemKey, PlaceOrderRequest req) {
        Order existing = orderMapper.selectByUserAndBizNo(userId, idemKey);
        if (existing != null) {
            return PlaceOrderResult.replayed(existing); // 重试请求不再远程扣库存
        }
        List<PricedItem> items = pricingService.price(req); // 事务外计价
        List<StockLine> lines =
                items.stream().map(i -> new StockLine(i.skuId(), i.quantity())).toList();
        String orderNo = orderNoGenerator.next(userId); // 嵌入用户 ID 低位
        inventoryApi.deduct(new DeductStockRequest(orderNo, lines));
        try {
            return PlaceOrderResult.created(placeOrderTxService.create(userId, orderNo, idemKey, req, items));
        } catch (RuntimeException e) { // 写单失败（含并发撞 uk_user_biz）：把库存还回去
            inventoryApi.restore(new RestoreStockRequest(orderNo, lines));
            if (e instanceof DuplicateKeyException) {
                return PlaceOrderResult.replayed(orderMapper.selectByUserAndBizNo(userId, idemKey));
            }
            throw e;
        }
    }
}
