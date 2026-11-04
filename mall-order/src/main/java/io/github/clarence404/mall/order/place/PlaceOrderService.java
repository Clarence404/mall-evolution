package io.github.clarence404.mall.order.place;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.common.error.CommonErrorCode;
import io.github.clarence404.mall.common.remote.RemoteBizException;
import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.inventory.api.ReleaseStockRequest;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.api.ReserveStockRequest;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 下单（阶段三）：先预占库存，再在本地事务里写单；结果未知按失败处理，立即释放预占。
 *
 * <p>结果未知时不重试 reserve，先释放再由全局异常处理器返回 SYSTEM_ERROR；客户端带同一个 Idempotency-Key 重试时，
 * 由于订单没写成功，会生成新的订单号重新预占。旧订单号的预占要么已被释放，要么被空回滚占位挡住。
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
            return PlaceOrderResult.replayed(existing);
        }
        List<PricedItem> items = pricingService.price(req);
        String orderNo = orderNoGenerator.next(userId); // 先有订单号，作为预占的幂等键
        List<ReserveLine> lines = ReserveLine.of(items);

        ReserveResult result;
        try {
            result = inventoryApi.reserve(new ReserveStockRequest(orderNo, lines)); // 读超时 800ms，不重试
        } catch (RemoteBizException e) {
            throw e; // 4xx，如 STOCK_INSUFFICIENT：明确失败，什么都没扣
        } catch (RuntimeException e) { // 超时、连接失败或 5xx：结果未知，按失败处理并立即释放
            releaseQuietly(orderNo, lines);
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

    /** 吞掉异常、只记日志：释放也失败时预占记录保持 RESERVED，交给库存服务的过期回查。 */
    private void releaseQuietly(String orderNo, List<ReserveLine> lines) {
        try {
            inventoryApi.release(new ReleaseStockRequest(orderNo, lines));
        } catch (RuntimeException e) {
            log.warn("释放预占失败，交给过期回查，orderNo={}", orderNo, e);
        }
    }
}
