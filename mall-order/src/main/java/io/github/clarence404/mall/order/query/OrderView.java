package io.github.clarence404.mall.order.query;

import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单对外视图：只有 orderNo，没有主键 id；金额与 skuId 一律按字符串输出（19 位整数超过 JS 安全整数）。
 *
 * <p>前端约定：遇到不认识的 status 显示「处理中」，不报错。
 */
public record OrderView(
        String orderNo,
        String status,
        String totalAmount,
        String freightAmount,
        String discountAmount,
        String payAmount,
        Receiver receiver,
        String remark,
        List<Item> items,
        LocalDateTime createdAt,
        LocalDateTime paidAt) {

    public static OrderView of(Order o) {
        return new OrderView(
                o.getOrderNo(),
                o.getStatus().name(),
                money(o.getTotalAmount()),
                money(o.getFreightAmount()),
                money(o.getDiscountAmount()),
                money(o.getPayAmount()),
                new Receiver(o.getReceiverName(), o.getReceiverPhone(), o.getReceiverAddr()),
                o.getRemark(),
                o.items().stream().map(Item::of).toList(),
                o.getCreatedAt(),
                o.getPaidAt());
    }

    static String money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public record Receiver(String name, String phone, String address) {}

    public record Item(
            String skuId, String productName, String productImage, String unitPrice, int quantity, String itemAmount) {

        static Item of(OrderItem i) {
            return new Item(
                    String.valueOf(i.getSkuId()),
                    i.getProductName(),
                    i.getProductImage(),
                    money(i.getUnitPrice()),
                    i.getQuantity(),
                    money(i.getItemAmount()));
        }
    }
}
