package io.github.clarence404.mall.order.query;

import io.github.clarence404.mall.common.json.Json;
import io.github.clarence404.mall.common.web.CursorResult;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.place.AddressForm;
import io.github.clarence404.mall.order.place.OrderLine;
import io.github.clarence404.mall.order.place.PlaceOrderRequest;
import io.github.clarence404.mall.order.place.PricedItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * 把查询结果变成响应的两步：领域对象转成对外的视图（金额格式化成字符串等），视图序列化成 JSON。
 * 订单详情是 1 单 2 条明细，订单列表是一页 20 单——和定位实验（experiments/i-locate）里两种查询返回的东西一样大。
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class OrderViewJsonBenchmark {

    private Order order;
    private List<Order> page;
    private OrderView detailView;
    private CursorResult<OrderListItemView> listView;

    @Setup
    public void setUp() {
        order = newOrder(0);
        page = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            page.add(newOrder(i));
        }
        detailView = OrderView.of(order);
        listView = toListView();
    }

    private static Order newOrder(int i) {
        PlaceOrderRequest req = new PlaceOrderRequest(
                List.of(new OrderLine(20001L, 1), new OrderLine(20002L, 1)),
                new AddressForm("实验", "13800000000", "高性能实验的种子订单"),
                "");
        List<PricedItem> items = List.of(
                new PricedItem(20001L, "实验商品 A", "", new BigDecimal("10.00"), 1),
                new PricedItem(20002L, "实验商品 B", "", new BigDecimal("20.00"), 1));
        return Order.create(800_000_000L + i, String.format("8%06d%06d", i, 199), "seed-" + i, req, items);
    }

    private CursorResult<OrderListItemView> toListView() {
        return new CursorResult<>(
                page.stream().map(OrderListItemView::of).toList(),
                OrderCursor.encode(page.getLast().getId()),
                true);
    }

    /** 订单详情：领域对象 → 视图。 */
    @Benchmark
    public OrderView detailToView() {
        return OrderView.of(order);
    }

    /** 订单详情：视图 → JSON。 */
    @Benchmark
    public String detailToJson() {
        return Json.write(detailView);
    }

    /** 订单列表（20 单）：领域对象 → 视图，含游标编码。 */
    @Benchmark
    public CursorResult<OrderListItemView> listToView() {
        return toListView();
    }

    /** 订单列表（20 单）：视图 → JSON。 */
    @Benchmark
    public String listToJson() {
        return Json.write(listView);
    }
}
