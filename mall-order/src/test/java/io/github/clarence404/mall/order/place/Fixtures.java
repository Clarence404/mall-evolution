package io.github.clarence404.mall.order.place;

import java.math.BigDecimal;
import java.util.List;

final class Fixtures {

    private Fixtures() {}

    static PlaceOrderRequest request() {
        return new PlaceOrderRequest(
                List.of(new OrderLine(20002L, 1), new OrderLine(20001L, 2)),
                new AddressForm("张三", "13800000000", "杭州市西湖区"),
                null);
    }

    static List<PricedItem> pricedItems() {
        return List.of(
                new PricedItem(20002L, "有机菠菜", "", new BigDecimal("12.80"), 1),
                new PricedItem(20001L, "红心柚", "", new BigDecimal("39.90"), 2));
    }
}
