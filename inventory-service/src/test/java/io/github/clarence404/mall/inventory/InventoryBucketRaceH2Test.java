package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.bucket.InventoryBucketMapper;
import io.github.clarence404.mall.inventory.bucket.InventoryBucketService;
import io.github.clarence404.mall.inventory.reserve.ReservationMapper;
import io.github.clarence404.mall.inventory.reserve.ReservationService;
import io.github.clarence404.mall.inventory.stock.InventoryMapper;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分桶库存的两个并发窗口，H2 MySQL 模式跑真实 SQL，不需要 Docker。
 *
 * <p>并发的先后顺序靠「在某条 SQL 执行前 / 后插入另一个事务」固定下来（给 Mapper 包一层动态代理），
 * 不靠 sleep 碰运气：同一个顺序每次都能复现。
 */
@SpringBootTest
@ActiveProfiles({"it", "h2"})
class InventoryBucketRaceH2Test {

    static final long SKU = 50001L;
    static final List<ReserveLine> ONE = List.of(new ReserveLine(SKU, 1));

    @Autowired
    InventoryBucketService bucketService;

    @Autowired
    ReservationService reservationService;

    @Autowired
    ReservationMapper reservationMapper;

    @Autowired
    InventoryMapper inventoryMapper;

    @Autowired
    InventoryBucketMapper bucketMapper;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OrderQueryApi orderQueryApi;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM t_inventory_bucket");
        jdbc.update("DELETE FROM t_inventory_reservation");
        jdbc.update("DELETE FROM t_inventory");
        jdbc.update("INSERT INTO t_inventory (sku_id, stock, reserved) VALUES (50001, 10, 0)");
    }

    /**
     * release 先到、迟到的 reserve 在它写空回滚占位之前抢先提交：占位插不进去（记录已存在），release 接着把这条预占
     * 由 RESERVED 改成 RELEASED，库存必须还回 reserve 选中的那个桶。
     *
     * <p>修复前 release 用的是方法开头读到的记录（那时还没有这条预占，桶号为空），把库存还到了主行：桶的 reserved 永久多 1、
     * 主行 reserved 变成 -1，两者相加为 0，每日对账也发现不了。
     */
    @Test
    void releaseRacingWithLateReserveReturnsStockToTheSameBucket() {
        bucketService.split(SKU, 2);
        AtomicBoolean fired = new AtomicBoolean();
        ReservationMapper racing =
                intercept(ReservationMapper.class, reservationMapper, "insertReleasedIfAbsent", () -> {
                    if (fired.compareAndSet(false, true)) {
                        reservationService.reserve("R1", ONE, null); // 另一个事务：迟到的 reserve 先提交
                    }
                });
        ReservationService releasing =
                new ReservationService(racing, inventoryMapper, bucketMapper, Duration.ofMinutes(30));

        transactionTemplate.executeWithoutResult(tx -> releasing.release("R1", ONE));

        assertThat(fired).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM t_inventory_reservation WHERE order_no = 'R1'", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(stock) FROM t_inventory_bucket", Integer.class))
                .isEqualTo(10); // 回到桶里
        assertThat(jdbc.queryForObject("SELECT SUM(reserved) FROM t_inventory_bucket", Integer.class))
                .isZero();
        assertThat(jdbc.queryForMap("SELECT stock, reserved FROM t_inventory WHERE sku_id = 50001"))
                .containsEntry("stock", 0)
                .containsEntry("reserved", 0); // 主行不受影响
    }

    /**
     * 合并分桶时并发来了一笔预占：合并读完各桶余量之后、删桶之前，预占从桶里扣走 1 件。
     *
     * <p>修复前合并只锁主行、对桶是普通读：预占成功，合并却按旧余量把 10 件全并回主行，凭空多出 1 件（超卖）。
     * 修复后合并对桶行 SELECT … FOR UPDATE：预占要么先提交（合并看到 reserved != 0 拒绝），要么等合并提交后发现桶没了、
     * 按库存不足失败。两种结果都满足「可售 + 桶内 + 已预占 = 10」。
     */
    @Test
    void mergeRacingWithReserveNeverCreatesStock() {
        bucketService.split(SKU, 2);
        AtomicReference<CompletableFuture<Void>> reserve = new AtomicReference<>();
        InventoryBucketMapper racing = (InventoryBucketMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {InventoryBucketMapper.class}, (proxy, method, args) -> {
                    Object result = invoke(bucketMapper, method, args);
                    if (method.getName().startsWith("selectBySku") && reserve.get() == null) {
                        // 合并已读完各桶余量：此时另一个线程来预占。拿不到行锁时它会一直等，这里最多等 300ms 就继续合并
                        reserve.set(CompletableFuture.runAsync(() -> reservationService.reserve("M1", ONE, null)));
                        try {
                            reserve.get().get(300, TimeUnit.MILLISECONDS);
                        } catch (Exception ignored) {
                            // 超时：预占被合并的行锁挡住了；或预占自己失败。结果都在下面的断言里核对
                        }
                    }
                    return result;
                });
        InventoryBucketService merging = new InventoryBucketService(inventoryMapper, racing);

        try {
            transactionTemplate.executeWithoutResult(tx -> merging.merge(SKU));
        } catch (IllegalStateException refused) {
            // 预占先提交时合并被拒绝，也是正确结果
        }
        try {
            reserve.get().get(5, TimeUnit.SECONDS);
        } catch (Exception failed) {
            // 预占按库存不足失败，也是正确结果
        }

        Integer sellable = jdbc.queryForObject("SELECT stock FROM t_inventory WHERE sku_id = 50001", Integer.class);
        Integer inBuckets =
                jdbc.queryForObject("SELECT COALESCE(SUM(stock), 0) FROM t_inventory_bucket", Integer.class);
        Integer reserved = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM t_inventory_reservation WHERE status = 1", Integer.class);
        assertThat(sellable + inBuckets + reserved).isEqualTo(10);
    }

    /** 预占记录指向的桶不存在（数据已经不一致）：确认与释放都报错回滚，由消息重投与告警暴露出来，不能静默跳过。 */
    @Test
    void confirmAndReleaseFailLoudlyWhenTheBucketRowIsGone() {
        bucketService.split(SKU, 2);
        reservationService.reserve("G1", ONE, null);
        jdbc.update("DELETE FROM t_inventory_bucket");

        assertThatThrownBy(() -> reservationService.confirm("G1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reservationService.release("G1", ONE)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM t_inventory_reservation WHERE order_no = 'G1'", Integer.class))
                .isEqualTo(1); // 事务回滚，预占仍是 RESERVED，等人工处理
    }

    /** 在 Mapper 的某个方法执行之前先跑一段代码（用来插入「另一个事务」），其余方法原样转给真实 Mapper。 */
    @SuppressWarnings("unchecked")
    private static <T> T intercept(Class<T> type, T target, String methodName, Runnable before) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getName().equals(methodName)) {
                before.run();
            }
            return invoke(target, method, args);
        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
