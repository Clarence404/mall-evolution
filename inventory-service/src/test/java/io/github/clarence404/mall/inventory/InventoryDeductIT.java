package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.inventory.api.StockLine;
import io.github.clarence404.mall.inventory.stock.InventoryService;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** stock >= n 条件更新在真实 MySQL 上不超卖；多 SKU 按 sku_id 升序加锁，交叉下单不死锁。需要 Docker。 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class InventoryDeductIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("mall_inventory");

    @Autowired
    InventoryService inventoryService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void noOversellUnderConcurrency() throws Exception {
        jdbc.update("REPLACE INTO t_inventory (sku_id, stock) VALUES (20001, 5)");
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String orderNo = "O" + i;
            tasks.add(() -> {
                try {
                    inventoryService.deduct(orderNo, List.of(new StockLine(20001L, 1)));
                    return true;
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
        assertThat(jdbc.queryForObject("SELECT stock FROM t_inventory WHERE sku_id = 20001", Integer.class))
                .isZero();
    }

    @Test
    void crossOrderedMultiSkuDeductionsDoNotDeadlock() throws Exception {
        jdbc.update("REPLACE INTO t_inventory (sku_id, stock) VALUES (30001, 1000), (30002, 1000)");
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            List<StockLine> lines = i % 2 == 0
                    ? List.of(new StockLine(30001L, 1), new StockLine(30002L, 1))
                    : List.of(new StockLine(30002L, 1), new StockLine(30001L, 1)); // 请求里顺序相反
            String orderNo = "X" + i;
            tasks.add(() -> {
                inventoryService.deduct(orderNo, lines);
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
}
