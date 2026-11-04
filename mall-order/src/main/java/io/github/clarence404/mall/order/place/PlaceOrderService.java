package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.error.CommonErrorCode;
import io.github.clarence404.mall.common.remote.RemoteBizException;
import io.github.clarence404.mall.inventory.api.ReleaseStockRequest;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.api.ReserveStockRequest;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 下单（阶段四）：计价 → 下单频控 → 经熔断器与舱壁预占 → 本地事务写单。
 *
 * <p>对 reserve 的结果分三类：明确失败直接抛；请求根本没发出去（熔断或舱壁拒绝）返回 SYSTEM_BUSY，库存没动、不需要 release；
 * 结果未知先释放。写接口一律不自动重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderService {

    private final OrderMapper orderMapper;
    private final PricingService pricingService;
    private final PlaceOrderTxService placeOrderTxService;
    private final OrderNoGenerator orderNoGenerator;
    private final InventoryReserveClient inventoryReserveClient;
    private final PendingOrderLimiter pendingOrderLimiter;
    private final PromoProperties promo;

    public PlaceOrderResult place(long userId, String idemKey, PlaceOrderRequest req) {
        Order existing = orderMapper.selectByUserAndBizNo(userId, idemKey);
        if (existing != null) {
            return PlaceOrderResult.replayed(existing);
        }
        List<PricedItem> items = pricingService.price(req);
        pendingOrderLimiter.check(userId, items); // 同一 SKU 待支付最多 2 单
        String orderNo = orderNoGenerator.next(userId); // 先有订单号，作为预占的幂等键
        List<ReserveLine> lines = ReserveLine.of(items);
        int payTimeoutMinutes =
                (int) promo.payTimeoutFor(items.stream().map(PricedItem::skuId).toList())
                        .toMinutes();

        ReserveResult result;
        try {
            // 经 inventory-reserve 熔断器与舱壁
            result = inventoryReserveClient.reserve(new ReserveStockRequest(orderNo, lines, payTimeoutMinutes));
        } catch (RemoteBizException e) {
            throw e; // 4xx，如 STOCK_INSUFFICIENT：明确失败，什么都没扣
        } catch (CallNotPermittedException | BulkheadFullException e) {
            throw new BizException(CommonErrorCode.SYSTEM_BUSY); // 没发出去：库存没动，不需要 release
        } catch (RuntimeException e) { // 超时、连接失败或 5xx：结果未知，按失败处理并立即释放
            releaseQuietly(orderNo, lines); // release 同样经过熔断器，被拒绝时交给过期回查
            throw e;
        }
        if (result == ReserveResult.REJECTED) { // 被空回滚占位挡住：明确没预占上，不用释放
            throw new BizException(CommonErrorCode.SYSTEM_ERROR, "订单 " + orderNo + " 的库存预占被拒绝，请重试");
        }
        try {
            return PlaceOrderResult.created(placeOrderTxService.create(userId, orderNo, idemKey, req, items));
        } catch (DuplicateKeyException e) { // 并发的重复请求已经下单，本次预占作废
            releaseQuietly(orderNo, lines);
            return PlaceOrderResult.replayed(orderMapper.selectByUserAndBizNo(userId, idemKey));
        } catch (RuntimeException e) { // 写订单失败
            releaseQuietly(orderNo, lines);
            throw e;
        }
    }

    /** 吞掉异常、只记日志：释放失败（含被熔断器拒绝）时预占记录保持 RESERVED，交给库存服务的过期回查。 */
    private void releaseQuietly(String orderNo, List<ReserveLine> lines) {
        try {
            inventoryReserveClient.release(new ReleaseStockRequest(orderNo, lines));
        } catch (RuntimeException e) {
            log.warn("释放预占失败，交给过期回查，orderNo={}", orderNo, e);
        }
    }
}
