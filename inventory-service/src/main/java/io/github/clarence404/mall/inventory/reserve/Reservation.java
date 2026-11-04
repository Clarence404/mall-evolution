package io.github.clarence404.mall.inventory.reserve;

import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 预占记录：(order_no, sku_id) 唯一，是 reserve / confirm / release 三个接口幂等的基础。 */
@Getter
@Setter
public class Reservation {

    public static final int RESERVED = 1;
    public static final int CONFIRMED = 2;
    public static final int RELEASED = 3;

    private long id;
    private String orderNo;
    private long skuId;
    private int quantity;
    private int status;
    private LocalDateTime expireAt;
    /** 分桶 SKU 预占时选中的桶，confirm / release 按它回到同一个桶；不分桶为 null。 */
    private Integer bucketNo;

    public static Reservation reserved(String orderNo, ReserveLine line, LocalDateTime expireAt) {
        Reservation r = new Reservation();
        r.id = Ids.next();
        r.orderNo = orderNo;
        r.skuId = line.skuId();
        r.quantity = line.quantity();
        r.status = RESERVED;
        r.expireAt = expireAt;
        return r;
    }

    public boolean isReleased() {
        return status == RELEASED;
    }
}
