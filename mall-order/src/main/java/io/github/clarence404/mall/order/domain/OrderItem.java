package io.github.clarence404.mall.order.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 订单明细：保存下单那一刻的商品名称、图片、单价快照。 */
@Getter
@Setter
public class OrderItem {

    private long id;
    private long orderId;
    private long skuId;
    private String productName;
    private String productImage;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal itemAmount;
    private LocalDateTime createdAt;
}
