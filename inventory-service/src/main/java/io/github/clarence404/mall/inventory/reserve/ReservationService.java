package io.github.clarence404.mall.inventory.reserve;

import static io.github.clarence404.mall.inventory.reserve.Reservation.CONFIRMED;
import static io.github.clarence404.mall.inventory.reserve.Reservation.RELEASED;
import static io.github.clarence404.mall.inventory.reserve.Reservation.RESERVED;
import static java.util.Comparator.comparing;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.inventory.api.InventoryErrorCode;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.bucket.InventoryBucketMapper;
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
 *   <li>分桶（阶段四）：bucket_count &gt; 0 的活动 SKU 从 hash(order_no) % n 号桶开始依次尝试，选中的桶号写进预占记录
 * </ul>
 */
@Slf4j
@Service
public class ReservationService {

    private static final Duration EXPIRE_AFTER_PAY_TIMEOUT = Duration.ofMinutes(15);

    private final ReservationMapper reservationMapper;
    private final InventoryMapper inventoryMapper;
    private final InventoryBucketMapper bucketMapper;
    private final Duration reservationTtl;

    public ReservationService(
            ReservationMapper reservationMapper,
            InventoryMapper inventoryMapper,
            InventoryBucketMapper bucketMapper,
            @Value("${mall.inventory.reservation-ttl:30m}") Duration reservationTtl) {
        this.reservationMapper = reservationMapper;
        this.inventoryMapper = inventoryMapper;
        this.bucketMapper = bucketMapper;
        this.reservationTtl = reservationTtl;
    }

    /**
     * expire_at 比订单支付超时多 15 分钟：普通订单 15 → 30 分钟，活动订单 5 → 20 分钟。
     *
     * @param payTimeoutMinutes 订单的支付超时（分钟）；为 null 时用 mall.inventory.reservation-ttl
     */
    @Transactional(rollbackFor = Exception.class)
    public ReserveResult reserve(String orderNo, List<ReserveLine> lines, Integer payTimeoutMinutes) {
        List<Reservation> existing = reservationMapper.selectByOrderNo(orderNo);
        if (!existing.isEmpty()) { // 重复请求，或 release 先到留下的空回滚占位
            return existing.getFirst().isReleased() ? ReserveResult.REJECTED : ReserveResult.OK;
        }
        LocalDateTime expireAt = LocalDateTime.now()
                .plus(
                        payTimeoutMinutes == null
                                ? reservationTtl
                                : Duration.ofMinutes(payTimeoutMinutes).plus(EXPIRE_AFTER_PAY_TIMEOUT));
        for (ReserveLine l :
                lines.stream().sorted(comparing(ReserveLine::skuId)).toList()) {
            Reservation r = Reservation.reserved(orderNo, l, expireAt);
            int buckets = bucketCount(l.skuId());
            if (buckets == 0) {
                reservationMapper.insert(r); // 并发时撞 uk_order_sku，整体回滚
                if (inventoryMapper.reserve(l.skuId(), l.quantity()) == 0) { // stock >= n 条件更新
                    throw insufficient(orderNo, l);
                }
            } else {
                r.setBucketNo(reserveInBuckets(orderNo, l, buckets));
                reservationMapper.insert(r);
            }
        }
        return ReserveResult.OK;
    }

    /**
     * 释放预占。每个 SKU 三步：
     *
     * <ol>
     *   <li>INSERT IGNORE 一行 RELEASED 占位：插进去了说明从没预占过（空回滚），不动库存
     *   <li>没插进去说明记录已存在：这时再按 (order_no, sku_id) 加锁读出这条记录，拿它的桶号与数量
     *   <li>RESERVED → RELEASED 条件更新成功才归还库存，分桶的回到同一个桶
     * </ol>
     *
     * <p>桶号与数量必须在第 1 步之后读：release 与迟到的 reserve 并发时，方法开头读到的可能还是「没有记录」，
     * 而 INSERT IGNORE 会等 reserve 提交后才返回 0；若沿用开头的快照，分桶 SKU 的库存就会被还到主行
     * （桶的 reserved 永久多 n、主行 reserved 变负，相加抵消，对账也发现不了）。归还数量也以预占记录为准，不信请求里的数量。
     */
    @Transactional(rollbackFor = Exception.class)
    public void release(String orderNo, List<ReserveLine> lines) {
        for (ReserveLine l :
                lines.stream().sorted(comparing(ReserveLine::skuId)).toList()) {
            // INSERT IGNORE ... status = 3：返回 1 说明从没预占过，返回 0 说明记录已存在
            // 不用 ON DUPLICATE KEY UPDATE id = id：Connector/J 默认返回 found rows，已存在也返回 1
            if (reservationMapper.insertReleasedIfAbsent(orderNo, l) == 1) {
                continue; // 空回滚：不动库存，只留下占位，挡住迟到的 reserve
            }
            // 当前读（FOR UPDATE）：拿到的一定是已提交的那条记录，并锁住它直到本事务结束
            Reservation current = reservationMapper.selectForUpdate(orderNo, l.skuId());
            if (current == null) {
                continue; // 不会发生：INSERT IGNORE 返回 0 说明记录存在，而预占记录从不删除
            }
            if (reservationMapper.transit(orderNo, l.skuId(), RESERVED, RELEASED) == 1) {
                if (current.getBucketNo() == null) {
                    inventoryMapper.unreserve(l.skuId(), current.getQuantity()); // stock +n, reserved -n
                } else if (bucketMapper.unreserve(l.skuId(), current.getBucketNo(), current.getQuantity()) == 0) {
                    throw bucketMissing(orderNo, current); // 回到同一个桶；桶不在了说明数据已不一致，回滚并报错
                }
            } else if (current.getStatus() == CONFIRMED) {
                log.error("预占已确认却收到释放，转人工核对，orderNo={}, skuId={}", orderNo, l.skuId());
            } // 其余 0 行：已释放（重复请求）
        }
    }

    /** confirm 只有一条条件更新 RESERVED → CONFIRMED，再把对应数量从 reserved 中减掉（分桶的回到同一个桶）。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String orderNo) {
        for (Reservation r : reservationMapper.selectByOrderNo(orderNo)) {
            if (reservationMapper.transit(orderNo, r.getSkuId(), RESERVED, CONFIRMED) == 1) {
                if (r.getBucketNo() == null) {
                    inventoryMapper.confirm(r.getSkuId(), r.getQuantity());
                } else if (bucketMapper.confirm(r.getSkuId(), r.getBucketNo(), r.getQuantity()) == 0) {
                    throw bucketMissing(orderNo, r); // 桶不在了：回滚，消息重投后进死信告警，不静默跳过
                }
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

    /**
     * 从 hash(order_no) % n 号桶开始执行条件更新，影响 0 行就试下一个桶，n 个桶都不够才返回 STOCK_INSUFFICIENT。
     * 库存库用 READ COMMITTED，条件不满足的 UPDATE 在语句结束就释放行锁，换桶时不会被空桶的锁拖住。
     */
    int reserveInBuckets(String orderNo, ReserveLine l, int buckets) {
        int start = Math.floorMod(orderNo.hashCode(), buckets);
        for (int i = 0; i < buckets; i++) {
            int bucketNo = (start + i) % buckets;
            if (bucketMapper.reserve(l.skuId(), bucketNo, l.quantity()) == 1) {
                return bucketNo;
            }
        }
        throw insufficient(orderNo, l);
    }

    private int bucketCount(long skuId) {
        Integer n = inventoryMapper.selectBucketCount(skuId);
        return n == null ? 0 : n;
    }

    /**
     * 预占记录写着桶号，桶行却不存在。合并分桶前会确认各桶 reserved 为 0，正常流程走不到这里；
     * 一旦出现就是数据不一致：抛非业务异常让事务回滚（预占保持 RESERVED），由消息重投、死信告警与人工处理接手。
     */
    private static IllegalStateException bucketMissing(String orderNo, Reservation r) {
        return new IllegalStateException(
                "分桶 " + r.getBucketNo() + " 不存在，无法处理预占，订单 " + orderNo + "，SKU " + r.getSkuId());
    }

    private static BizException insufficient(String orderNo, ReserveLine l) {
        return new BizException(InventoryErrorCode.STOCK_INSUFFICIENT, "SKU " + l.skuId() + " 库存不足，订单 " + orderNo);
    }
}
