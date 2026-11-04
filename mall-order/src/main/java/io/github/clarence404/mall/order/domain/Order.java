package io.github.clarence404.mall.order.domain;

import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.order.place.PlaceOrderRequest;
import io.github.clarence404.mall.order.place.PricedItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Order {

    private long id;
    private String orderNo;
    private long userId;
    private String bizNo;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private BigDecimal freightAmount;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
    private String receiverName;
    private String receiverPhone;
    private String receiverAddr;
    private String remark;
    private LocalDateTime paidAt;
    private int version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<OrderItem> items = new ArrayList<>();

    /** 阶段三：订单号由 OrderNoGenerator 先生成（嵌入用户 ID 低位），主键仍是雪花 ID。 */
    public static Order create(
            long userId, String orderNo, String bizNo, PlaceOrderRequest req, List<PricedItem> items) {
        return create(Ids.next(), orderNo, userId, bizNo, req, items);
    }

    static Order create(
            long id, String orderNo, long userId, String bizNo, PlaceOrderRequest req, List<PricedItem> items) {
        Order order = new Order();
        order.id = id;
        order.orderNo = orderNo;
        order.userId = userId;
        order.bizNo = bizNo;
        order.status = OrderStatus.PENDING_PAYMENT;
        order.createdAt = LocalDateTime.now();
        BigDecimal total = BigDecimal.ZERO;
        for (PricedItem pi : items) {
            OrderItem item = new OrderItem();
            item.setId(Ids.next());
            item.setOrderId(id);
            item.setSkuId(pi.skuId());
            item.setProductName(pi.productName());
            item.setProductImage(pi.productImage() == null ? "" : pi.productImage());
            item.setUnitPrice(pi.unitPrice());
            item.setQuantity(pi.quantity());
            item.setItemAmount(pi.itemAmount());
            item.setCreatedAt(order.createdAt);
            order.items.add(item);
            total = total.add(pi.itemAmount());
        }
        order.totalAmount = total.setScale(2, RoundingMode.HALF_UP);
        order.freightAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        order.discountAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        order.payAmount = order.totalAmount.add(order.freightAmount).subtract(order.discountAmount);
        order.receiverName = req.address().receiver();
        order.receiverPhone = req.address().phone();
        order.receiverAddr = req.address().detail();
        order.remark = req.remark() == null ? "" : req.remark();
        return order;
    }

    public List<OrderItem> items() {
        return items;
    }
}
