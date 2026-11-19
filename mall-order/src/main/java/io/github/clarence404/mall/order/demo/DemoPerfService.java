package io.github.clarence404.mall.order.demo;

import io.github.clarence404.mall.common.id.Ids;
import io.github.clarence404.mall.order.demo.DemoPerfMapper.JoinedRow;
import io.github.clarence404.mall.order.demo.DemoPerfMapper.SeedItem;
import io.github.clarence404.mall.order.demo.DemoPerfMapper.SeedOrder;
import io.github.clarence404.mall.order.domain.Order;
import io.github.clarence404.mall.order.domain.OrderItem;
import io.github.clarence404.mall.order.domain.OrderItemMapper;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 仅演示环境：高性能实验「数据访问」的三组对照——偏移量分页 / 延迟关联 / 游标分页，逐单查明细（N+1）/ 一次 IN / 一次 JOIN，
 * 逐条插入 / 一个事务里逐条 / 一条语句多行 / JDBC 批量。正式代码用的是每组里的最后一种或倒数第二种（游标分页、
 * 一条语句多行），这里把「不该那样写」的写法也实现出来，在同一份数据上量差多少。见 experiments/j-data-access。
 *
 * <p>种子数据（{@link #seed}）：从 {@value #FIRST_USER_ID} 起的 users 个普通用户，每人每轮一单；另有一个大客户
 * （用户 ID {@value #HOT_USER_ID}）每轮 hotPerRound 单。一轮之内普通用户和大客户的订单交错着写进去，所以同一个用户的订单
 * 在主键上是散开的——和线上「各个用户的订单混在一起按时间增长」一样，按用户翻页时每一行都落在不同的数据页上。
 * 订单号是「8 + 6 位用户序号 + 6 位第几单」，压测脚本不用查库就能拼出任意一单的订单号。每单 2 条明细，状态是已取消（50），
 * 不会被超时关单、对账等任务扫到。
 */
@Slf4j
@Service
@Profile("demo")
class DemoPerfService {

    static final long FIRST_USER_ID = 800_000_000L;
    static final int HOT_USER_INDEX = 999_999;
    static final long HOT_USER_ID = FIRST_USER_ID + HOT_USER_INDEX;
    private static final int SEED_CHUNK = 1000;
    private static final BigDecimal PRICE_A = new BigDecimal("10.00");
    private static final BigDecimal PRICE_B = new BigDecimal("20.00");
    private static final BigDecimal PAY = new BigDecimal("30.00");
    private static final String ITEM_SQL =
            "INSERT INTO t_order_item (id, order_id, sku_id, product_name, product_image,"
                    + " unit_price, quantity, item_amount, created_at) VALUES (?, ?, ?, ?, '', ?, ?, ?, ?)";

    private final DemoPerfMapper mapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final boolean rewriteBatchedStatements;

    DemoPerfService(
            DemoPerfMapper mapper,
            OrderMapper orderMapper,
            OrderItemMapper orderItemMapper,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${spring.datasource.hikari.data-source-properties.rewriteBatchedStatements:false}")
                    boolean rewriteBatchedStatements) {
        this.mapper = mapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = new TransactionTemplate(transactionManager);
        this.rewriteBatchedStatements = rewriteBatchedStatements;
    }

    static String orderNo(int userIndex, int seq) {
        return String.format("8%06d%06d", userIndex, seq);
    }

    // ---------------------------------------------------------------- 种子数据

    /** 灌第 [fromRound, toRound) 轮。每轮 users + hotPerRound 单，重复执行不会多出数据。 */
    SeedResult seed(int fromRound, int toRound, int users, int hotPerRound) {
        long t0 = System.nanoTime();
        long orders = 0;
        long items = 0;
        for (int round = fromRound; round < toRound; round++) {
            List<SeedOrder> batch = new ArrayList<>(SEED_CHUNK);
            int perRound = users + hotPerRound;
            int normal = 0;
            int hot = 0;
            for (int j = 0; j < perRound; j++) {
                // 交错：按比例轮到大客户，其余轮到下一个普通用户
                boolean takeHot = hot < hotPerRound && (long) hot * perRound <= (long) j * hotPerRound;
                int userIndex = takeHot ? HOT_USER_INDEX : normal;
                int seq = takeHot ? round * hotPerRound + hot : round;
                if (takeHot) {
                    hot++;
                } else {
                    normal++;
                }
                String orderNo = orderNo(userIndex, seq);
                batch.add(new SeedOrder(
                        Ids.next(), orderNo, FIRST_USER_ID + userIndex, "seed-" + orderNo, PAY, LocalDateTime.now()));
                if (batch.size() == SEED_CHUNK || j == perRound - 1) {
                    long[] n = writeSeedChunk(batch);
                    orders += n[0];
                    items += n[1];
                    batch = new ArrayList<>(SEED_CHUNK);
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("种子订单：第 {}–{} 轮，新写入 {} 单、{} 条明细，用时 {}ms", fromRound, toRound - 1, orders, items, ms);
        return new SeedResult(fromRound, toRound, orders, items, ms);
    }

    /**
     * 一批订单和它们的明细放在一个事务里：要么都写进去，要么都没有。重复执行时这一批的订单已经在了（INSERT IGNORE 写入 0 行），
     * 明细也就不用再补；中途失败重试时不会留下「有订单没明细」的半截数据。返回新写入的订单数与明细数。
     */
    private long[] writeSeedChunk(List<SeedOrder> batch) {
        return tx.execute(status -> {
            int inserted = mapper.insertOrders(batch);
            if (inserted == 0) {
                return new long[] {0, 0};
            }
            if (inserted != batch.size()) {
                throw new IllegalStateException("种子订单这一批只写进去一部分（" + inserted + " / " + batch.size() + "），数据被别的东西动过");
            }
            List<SeedItem> rows = new ArrayList<>(batch.size() * 2);
            for (SeedOrder o : batch) {
                rows.add(item(o.id(), 20001L, "实验商品 A", PRICE_A, o.createdAt()));
                rows.add(item(o.id(), 20002L, "实验商品 B", PRICE_B, o.createdAt()));
            }
            return new long[] {inserted, mapper.insertItems(rows)};
        });
    }

    SeedStatus seedStatus(int users) {
        return new SeedStatus(
                mapper.countOrders(FIRST_USER_ID),
                mapper.countOrders(FIRST_USER_ID + users - 1),
                mapper.countOrders(HOT_USER_ID));
    }

    private static SeedItem item(long orderId, long skuId, String name, BigDecimal price, LocalDateTime at) {
        return new SeedItem(Ids.next(), orderId, skuId, name, price, 1, price, at);
    }

    // ---------------------------------------------------------------- 批量写

    /**
     * 往 t_order_item 写 rows 行，按 mode 指定的写法，返回用时。写进去的行挂在一个不存在的订单上，量完删掉（删除不计时）。
     *
     * <pre>
     * single        一条语句一行，每条自动提交
     * single-tx     一条语句一行，每 batchSize 行放在一个事务里提交一次
     * multi-values  一条语句 batchSize 行（INSERT ... VALUES (...), (...)），每条自动提交
     * jdbc-batch    JDBC 批量（addBatch / executeBatch），每 batchSize 行一批、一个事务；
     *               驱动把这一批怎么发出去取决于连接属性 rewriteBatchedStatements（启动参数里给）
     * </pre>
     */
    BatchResult batchInsert(String mode, int rows, int batchSize) {
        long orderId = -Ids.next();
        LocalDateTime now = LocalDateTime.now();
        List<SeedItem> all = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            all.add(item(orderId, 20001L, "批量写实验", PRICE_A, now));
        }
        long t0 = System.nanoTime();
        switch (mode) {
            case "single" -> all.forEach(mapper::insertItem);
            case "single-tx" ->
                chunks(all, batchSize).forEach(c -> tx.executeWithoutResult(s -> c.forEach(mapper::insertItem)));
            case "multi-values" -> chunks(all, batchSize).forEach(mapper::insertItems);
            case "jdbc-batch" -> chunks(all, batchSize).forEach(c -> tx.executeWithoutResult(s -> jdbcBatch(c)));
            default ->
                throw new IllegalArgumentException("mode 只能是 single / single-tx / multi-values / jdbc-batch：" + mode);
        }
        long micros = (System.nanoTime() - t0) / 1_000;
        while (mapper.deleteItemsOfOrder(orderId, 5000) > 0) {
            // 分批删，免得一条语句删几万行
        }
        BatchResult r = new BatchResult(
                mode,
                rows,
                batchSize,
                rewriteBatchedStatements,
                micros / 1000,
                Math.round(rows * 1_000_000.0 / micros));
        log.info("批量写实验：{}", r);
        return r;
    }

    private void jdbcBatch(List<SeedItem> chunk) {
        jdbcTemplate.batchUpdate(ITEM_SQL, chunk, chunk.size(), (ps, i) -> {
            ps.setLong(1, i.id());
            ps.setLong(2, i.orderId());
            ps.setLong(3, i.skuId());
            ps.setString(4, i.productName());
            ps.setBigDecimal(5, i.unitPrice());
            ps.setInt(6, i.quantity());
            ps.setBigDecimal(7, i.itemAmount());
            ps.setTimestamp(8, Timestamp.valueOf(i.createdAt()));
        });
    }

    private static <T> List<List<T>> chunks(List<T> all, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            out.add(all.subList(i, Math.min(all.size(), i + size)));
        }
        return out;
    }

    // ---------------------------------------------------------------- 深分页

    /** 翻到第 offset 行之后的一页。cursor 模式不看 offset，从 cursorId 往后取（cursorId 为空是第一页）。 */
    List<OrderRow> page(String mode, long userId, int offset, int size, Long cursorId) {
        List<Order> rows = switch (mode) {
            case "offset" -> mapper.pageByOffset(userId, offset, size);
            case "deferred" -> mapper.pageByDeferredJoin(userId, offset, size);
            case "cursor" -> orderMapper.listByUser(userId, null, cursorId, null, size, true);
            default -> throw new IllegalArgumentException("mode 只能是 offset / deferred / cursor：" + mode);
        };
        return rows.stream().map(OrderRow::of).toList();
    }

    /** 游标分页翻到同样深度时该带的游标：第 offset 行的上一行的主键（offset 为 0 时没有游标）。 */
    Long cursorAt(long userId, int offset) {
        return offset <= 0 ? null : mapper.idAtOffset(userId, offset - 1);
    }

    // ---------------------------------------------------------------- 带明细的列表

    /**
     * 最近 size 单，每单带明细。
     *
     * <pre>
     * n-plus-one  查一页订单，再逐单查明细：1 + size 条语句
     * in          查一页订单，再用一条 IN 查这一页的全部明细：2 条语句
     * join        一条语句连表查出来，在内存里按订单归组：1 条语句
     * </pre>
     */
    List<OrderWithItems> listWithItems(String mode, long userId, int size) {
        if ("join".equals(mode)) {
            Map<Long, OrderWithItems> byOrder = new LinkedHashMap<>();
            for (JoinedRow r : mapper.listWithItemsJoined(userId, size)) {
                OrderWithItems o = byOrder.computeIfAbsent(
                        r.orderId(),
                        id -> new OrderWithItems(
                                r.orderNo(), statusName(r.status()), r.payAmount(), r.createdAt(), new ArrayList<>()));
                if (r.skuId() != null) {
                    o.items().add(new ItemRow(r.skuId(), r.productName(), r.unitPrice(), r.quantity(), r.itemAmount()));
                }
            }
            return List.copyOf(byOrder.values());
        }
        List<Order> orders = orderMapper.listByUser(userId, null, null, null, size, true);
        Map<Long, List<ItemRow>> items = new LinkedHashMap<>();
        switch (mode) {
            case "n-plus-one" -> {
                for (Order o : orders) {
                    items.put(
                            o.getId(),
                            orderItemMapper.selectByOrderId(o.getId()).stream()
                                    .map(ItemRow::of)
                                    .toList());
                }
            }
            case "in" -> {
                if (!orders.isEmpty()) {
                    for (OrderItem i : mapper.selectItemsByOrderIds(
                            orders.stream().map(Order::getId).toList())) {
                        items.computeIfAbsent(i.getOrderId(), k -> new ArrayList<>())
                                .add(ItemRow.of(i));
                    }
                }
            }
            default -> throw new IllegalArgumentException("mode 只能是 n-plus-one / in / join：" + mode);
        }
        return orders.stream()
                .map(o -> new OrderWithItems(
                        o.getOrderNo(),
                        o.getStatus().name(),
                        o.getPayAmount(),
                        o.getCreatedAt(),
                        items.getOrDefault(o.getId(), List.of())))
                .toList();
    }

    private static String statusName(int code) {
        for (OrderStatus s : OrderStatus.values()) {
            if (s.getCode() == code) {
                return s.name();
            }
        }
        return String.valueOf(code);
    }

    record SeedResult(int fromRound, int toRound, long orders, long items, long elapsedMs) {}

    /** 第一个普通用户、最后一个普通用户、大客户各有多少单：都等于预期值才算灌完。 */
    record SeedStatus(long firstUserOrders, long lastUserOrders, long hotUserOrders) {}

    record BatchResult(
            String mode, int rows, int batchSize, boolean rewriteBatchedStatements, long elapsedMs, long rowsPerSec) {}

    record OrderRow(String orderNo, String status, BigDecimal payAmount, LocalDateTime createdAt) {
        static OrderRow of(Order o) {
            return new OrderRow(o.getOrderNo(), o.getStatus().name(), o.getPayAmount(), o.getCreatedAt());
        }
    }

    record ItemRow(long skuId, String productName, BigDecimal unitPrice, int quantity, BigDecimal itemAmount) {
        static ItemRow of(OrderItem i) {
            return new ItemRow(i.getSkuId(), i.getProductName(), i.getUnitPrice(), i.getQuantity(), i.getItemAmount());
        }
    }

    record OrderWithItems(
            String orderNo, String status, BigDecimal payAmount, LocalDateTime createdAt, List<ItemRow> items) {}
}
