package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.api.ReserveResult;
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
 * 预占在真实 MySQL 上：stock >= n 不超卖、多 SKU 按 sku_id 升序不死锁、reserve 与 release 并发时由 uk_order_sku 决定先后。
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
                    return reservationService.reserve(orderNo, List.of(new ReserveLine(20001L, 1))) == ReserveResult.OK;
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
                reservationService.reserve(orderNo, lines);
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
                Future<?> reserve = pool.submit(() -> safely(() -> reservationService.reserve(orderNo, lines)));
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

    private static Object safely(Callable<?> call) {
        try {
            return call.call();
        } catch (Exception e) {
            return null; // 并发撞 uk_order_sku 的一方整体回滚，属预期
        }
    }
}
