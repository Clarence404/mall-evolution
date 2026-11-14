package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.api.ReserveResult;
import io.github.clarence404.mall.inventory.bucket.InventoryBucketService;
import io.github.clarence404.mall.inventory.reserve.ReservationService;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 预占在真实 MySQL 上：stock >= n 不超卖、多 SKU 按 sku_id 升序不死锁、reserve 与 release 并发时由 uk_order_sku 决定先后；
 * 分桶 SKU 上 release 回到同一个桶、合并分桶与预占并发不多出库存。
 * 需要 Docker。
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class InventoryDeductIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("mall_inventory");

    @Autowired
    ReservationService reservationService;

    @Autowired
    InventoryBucketService bucketService;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OrderQueryApi orderQueryApi;

    @Test
    void noOversellUnderConcurrency() throws Exception {
        jdbc.update("REPLACE INTO t_inventory (sku_id, stock, reserved) VALUES (20001, 5, 0)");
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String orderNo = "O" + i;
            tasks.add(() -> {
                try {
                    return reservationService.reserve(orderNo, List.of(new ReserveLine(20001L, 1)), null)
                            == ReserveResult.OK;
                } catch (BizException e) {
                    return false;
                }
            });
        }
        int success = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                success += f.get() ? 1 : 0;
            }
        }
        assertThat(success).isEqualTo(5);
        assertThat(jdbc.queryForMap("SELECT stock, reserved FROM t_inventory WHERE sku_id = 20001"))
                .containsEntry("stock", 0)
                .containsEntry("reserved", 5);
    }

    @Test
    void crossOrderedMultiSkuReservationsDoNotDeadlock() throws Exception {
        jdbc.update("REPLACE INTO t_inventory (sku_id, stock, reserved) VALUES (30001, 1000, 0), (30002, 1000, 0)");
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            List<ReserveLine> lines = i % 2 == 0
                    ? List.of(new ReserveLine(30001L, 1), new ReserveLine(30002L, 1))
                    : List.of(new ReserveLine(30002L, 1), new ReserveLine(30001L, 1)); // 请求里顺序相反
            String orderNo = "X" + i;
            tasks.add(() -> {
                reservationService.reserve(orderNo, lines, null);
                return null;
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        }
        assertThat(jdbc.queryForObject(
                        "SELECT SUM(stock) FROM t_inventory WHERE sku_id IN (30001, 30002)", Integer.class))
                .isEqualTo(1900);
    }

    @Test
    void racingReserveAndReleaseNeverLeakStock() throws Exception {
        jdbc.update("REPLACE INTO t_inventory (sku_id, stock, reserved) VALUES (40001, 100, 0)");
        for (int round = 0; round < 30; round++) {
            String orderNo = "R" + round;
            List<ReserveLine> lines = List.of(new ReserveLine(40001L, 1));
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<?> reserve = pool.submit(() -> safely(() -> reservationService.reserve(orderNo, lines, null)));
                Future<?> release = pool.submit(() -> safely(() -> {
                    reservationService.release(orderNo, lines);
                    return null;
                }));
                reserve.get();
                release.get();
            }
            reservationService.release(orderNo, lines); // 订单最终没写成：无论谁先到，释放后库存都要回来
        }
        assertThat(jdbc.queryForMap("SELECT stock, reserved FROM t_inventory WHERE sku_id = 40001"))
                .containsEntry("stock", 100)
                .containsEntry("reserved", 0);
    }

    /**
     * 分桶 SKU 上 reserve 与 release 竞态：无论谁先到，释放后库存都回到桶里，主行不被动到。
     * release 若沿用方法开头的快照（那时还没有预占记录、桶号为空），会把库存还到主行——主行 reserved 变负。
     */
    @Test
    void racingReserveAndReleaseOnBucketedSkuReturnsStockToBuckets() throws Exception {
        jdbc.update("DELETE FROM t_inventory_bucket WHERE sku_id = 40002");
        jdbc.update("REPLACE INTO t_inventory (sku_id, stock, reserved) VALUES (40002, 100, 0)");
        bucketService.split(40002L, 4);
        for (int round = 0; round < 30; round++) {
            String orderNo = "BR" + round;
            List<ReserveLine> lines = List.of(new ReserveLine(40002L, 1));
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<?> reserve = pool.submit(() -> safely(() -> reservationService.reserve(orderNo, lines, null)));
                Future<?> release = pool.submit(() -> safely(() -> {
                    reservationService.release(orderNo, lines);
                    return null;
                }));
                reserve.get();
                release.get();
            }
            reservationService.release(orderNo, lines);
        }
        assertThat(jdbc.queryForObject("SELECT SUM(stock) FROM t_inventory_bucket WHERE sku_id = 40002", Integer.class))
                .isEqualTo(100);
        assertThat(jdbc.queryForObject(
                        "SELECT SUM(reserved) FROM t_inventory_bucket WHERE sku_id = 40002", Integer.class))
                .isZero();
        assertThat(jdbc.queryForMap("SELECT stock, reserved FROM t_inventory WHERE sku_id = 40002"))
                .containsEntry("stock", 0)
                .containsEntry("reserved", 0);
    }

    /**
     * 合并分桶与预占并发：合并对桶行加了 FOR UPDATE，预占要么先提交（合并被拒绝），要么等合并提交后按库存不足失败。
     * 每一轮结束都满足「主行可售 + 桶内可售 + 仍预占的数量 = 初始库存」，不会凭空多出库存。
     */
    @Test
    void mergeRacingWithReservationsNeverCreatesStock() throws Exception {
        for (int round = 0; round < 15; round++) {
            long sku = 41000L + round;
            jdbc.update("REPLACE INTO t_inventory (sku_id, stock, reserved) VALUES (?, 10, 0)", sku);
            bucketService.split(sku, 2);
            String prefix = "MG" + round + "-";
            try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    String orderNo = prefix + i;
                    futures.add(pool.submit(() ->
                            safely(() -> reservationService.reserve(orderNo, List.of(new ReserveLine(sku, 1)), null))));
                }
                futures.add(pool.submit(() -> safely(() -> {
                    bucketService.merge(sku);
                    return null;
                })));
                for (Future<?> f : futures) {
                    f.get();
                }
            }
            Integer sellable =
                    jdbc.queryForObject("SELECT stock FROM t_inventory WHERE sku_id = ?", Integer.class, sku);
            Integer inBuckets = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(stock), 0) FROM t_inventory_bucket WHERE sku_id = ?", Integer.class, sku);
            Integer reserved = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(quantity), 0) FROM t_inventory_reservation WHERE sku_id = ? AND status = 1",
                    Integer.class,
                    sku);
            assertThat(sellable + inBuckets + reserved).as("第 %d 轮", round).isEqualTo(10);
            // 合并成功（bucket_count 回到 0）时不应还有指向桶的预占
            Integer bucketCount =
                    jdbc.queryForObject("SELECT bucket_count FROM t_inventory WHERE sku_id = ?", Integer.class, sku);
            if (bucketCount == 0) {
                assertThat(reserved).as("第 %d 轮：合并后不应留下分桶预占", round).isZero();
            }
        }
    }

    private static Object safely(Callable<?> call) {
        try {
            return call.call();
        } catch (Exception e) {
            return null; // 并发撞 uk_order_sku 的一方整体回滚，属预期
        }
    }
}
