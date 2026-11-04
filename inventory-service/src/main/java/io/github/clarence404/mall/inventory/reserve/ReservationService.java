package io.github.clarence404.mall.inventory.reserve;

import static io.github.clarence404.mall.inventory.reserve.Reservation.CONFIRMED;
import static io.github.clarence404.mall.inventory.reserve.Reservation.RELEASED;
import static io.github.clarence404.mall.inventory.reserve.Reservation.RESERVED;
import static java.util.Comparator.comparing;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.inventory.api.InventoryErrorCode;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.stock.InventoryMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存预占的三个接口。没有独立的事务协调者：订单服务自己就是协调者，订单状态就是全局事务状态。
 *
 * <ul>
 *   <li>空回滚：release 先到、库里没有预占记录时写一行 RELEASED 占位，挡住迟到的 reserve
 *   <li>悬挂：迟到的 reserve 查到 RELEASED 占位，返回 REJECTED，不扣库存；两者并发时由 uk_order_sku 决定先后
 * </ul>
 */
@Slf4j
@Service
public class ReservationService {

    private final ReservationMapper reservationMapper;
    private final InventoryMapper inventoryMapper;
    private final Duration reservationTtl;

    public ReservationService(
            ReservationMapper reservationMapper,
            InventoryMapper inventoryMapper,
            @Value("${mall.inventory.reservation-ttl:30m}") Duration reservationTtl) {
        this.reservationMapper = reservationMapper;
        this.inventoryMapper = inventoryMapper;
        this.reservationTtl = reservationTtl;
    }

    @Transactional(rollbackFor = Exception.class)
    public ReserveResult reserve(String orderNo, List<ReserveLine> lines) {
        List<Reservation> existing = reservationMapper.selectByOrderNo(orderNo);
        if (!existing.isEmpty()) { // 重复请求，或 release 先到留下的空回滚占位
            return existing.getFirst().isReleased() ? ReserveResult.REJECTED : ReserveResult.OK;
        }
        LocalDateTime expireAt = LocalDateTime.now().plus(reservationTtl);
        for (ReserveLine l :
                lines.stream().sorted(comparing(ReserveLine::skuId)).toList()) {
            reservationMapper.insert(Reservation.reserved(orderNo, l, expireAt)); // 并发时撞 uk_order_sku，整体回滚
            if (inventoryMapper.reserve(l.skuId(), l.quantity()) == 0) { // stock >= n 条件更新
                throw new BizException(
                        InventoryErrorCode.STOCK_INSUFFICIENT, "SKU " + l.skuId() + " 库存不足，订单 " + orderNo);
            }
        }
        return ReserveResult.OK;
    }

    @Transactional(rollbackFor = Exception.class)
    public void release(String orderNo, List<ReserveLine> lines) {
        for (ReserveLine l :
                lines.stream().sorted(comparing(ReserveLine::skuId)).toList()) {
            // INSERT IGNORE ... status = 3：返回 1 说明从没预占过，返回 0 说明记录已存在
            // 不用 ON DUPLICATE KEY UPDATE id = id：Connector/J 默认返回 found rows，已存在也返回 1
            if (reservationMapper.insertReleasedIfAbsent(orderNo, l) == 1) {
                continue; // 空回滚：不动库存，只留下占位，挡住迟到的 reserve
            }
            if (reservationMapper.transit(orderNo, l.skuId(), RESERVED, RELEASED) == 1) {
                inventoryMapper.unreserve(l.skuId(), l.quantity()); // stock +n, reserved -n
            } else if (isConfirmed(orderNo, l.skuId())) {
                log.error("预占已确认却收到释放，转人工核对，orderNo={}, skuId={}", orderNo, l.skuId());
            } // 其余 0 行：已释放（重复请求）
        }
    }

    /** confirm 只有一条条件更新 RESERVED → CONFIRMED，再把对应数量从 reserved 中减掉。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String orderNo) {
        for (Reservation r : reservationMapper.selectByOrderNo(orderNo)) {
            if (reservationMapper.transit(orderNo, r.getSkuId(), RESERVED, CONFIRMED) == 1) {
                inventoryMapper.confirm(r.getSkuId(), r.getQuantity());
            }
        }
    }

    /** 过期回查用：按库里的预占记录释放（订单不存在或已取消）。 */
    @Transactional(rollbackFor = Exception.class)
    public void releaseByOrderNo(String orderNo) {
        List<ReserveLine> lines = reservationMapper.selectByOrderNo(orderNo).stream()
                .filter(r -> r.getStatus() == RESERVED)
                .map(r -> new ReserveLine(r.getSkuId(), r.getQuantity()))
                .toList();
        if (!lines.isEmpty()) {
            release(orderNo, lines);
        }
    }

    private boolean isConfirmed(String orderNo, long skuId) {
        return reservationMapper.selectByOrderNo(orderNo).stream()
                .anyMatch(r -> r.getSkuId() == skuId && r.getStatus() == CONFIRMED);
    }
}
