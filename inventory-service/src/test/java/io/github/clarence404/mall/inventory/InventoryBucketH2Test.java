package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.inventory.api.ReserveLine;
import io.github.clarence404.mall.inventory.bucket.InventoryBucketService;
import io.github.clarence404.mall.inventory.reserve.ReservationService;
import io.github.clarence404.mall.inventory.stock.InventoryService;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 热点库存分桶走真实 SQL（H2 MySQL 模式）：拆桶、按桶预占与换桶、回到同一个桶确认 / 释放、再平衡、合并。 */
@SpringBootTest
@ActiveProfiles({"it", "h2"})
class InventoryBucketH2Test {

    @Autowired
    InventoryBucketService bucketService;

    @Autowired
    ReservationService reservationService;

    @Autowired
    InventoryService inventoryService;

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

    @Test
    void splitReserveAcrossBucketsConfirmReleaseAndMerge() {
        bucketService.split(50001L, 3);
        assertThat(bucketStocks()).containsExactly(4, 3, 3);
        assertThat(inventoryService.listStocks(List.of(50001L)).getFirst().stock())
                .isEqualTo(10); // 展示 = 各桶之和

        for (int i = 0; i < 10; i++) {
            reservationService.reserve("B" + i, List.of(new ReserveLine(50001L, 1)), null);
        }
        assertThat(bucketStocks()).containsExactly(0, 0, 0); // 桶空换桶，10 件都卖出去
        assertThatThrownBy(() -> reservationService.reserve("B10", List.of(new ReserveLine(50001L, 1)), null))
                .isInstanceOf(BizException.class); // 每个桶都不够才返回 STOCK_INSUFFICIENT

        Integer bucketOfB0 = jdbc.queryForObject(
                "SELECT bucket_no FROM t_inventory_reservation WHERE order_no = 'B0'", Integer.class);
        reservationService.release("B0", List.of(new ReserveLine(50001L, 1)));
        assertThat(bucketStocks().get(bucketOfB0)).isEqualTo(1); // 回到同一个桶

        for (int i = 1; i < 10; i++) {
            reservationService.confirm("B" + i);
        }
        assertThat(jdbc.queryForObject("SELECT SUM(reserved) FROM t_inventory_bucket", Integer.class))
                .isZero();

        bucketService.merge(50001L);
        assertThat(jdbc.queryForMap("SELECT stock, bucket_count FROM t_inventory WHERE sku_id = 50001"))
                .containsEntry("stock", 1)
                .containsEntry("bucket_count", 0);
    }

    @Test
    void rebalanceMovesHalfOfRichestBucketIntoEmptyOnes() {
        bucketService.split(50001L, 2);
        jdbc.update("UPDATE t_inventory_bucket SET stock = 0 WHERE sku_id = 50001 AND bucket_no = 1");
        jdbc.update("UPDATE t_inventory_bucket SET stock = 8 WHERE sku_id = 50001 AND bucket_no = 0");

        bucketService.rebalance(50001L);

        assertThat(bucketStocks()).containsExactly(4, 4);
    }

    @Test
    void mergeIsRefusedWhileBucketReservationsAreOutstanding() {
        bucketService.split(50001L, 2);
        reservationService.reserve("M1", List.of(new ReserveLine(50001L, 1)), null);

        assertThatThrownBy(() -> bucketService.merge(50001L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activityOrderReservationExpiresTwentyMinutesLater() {
        reservationService.reserve("P1", List.of(new ReserveLine(50001L, 1)), 5);
        Long minutes = jdbc.queryForObject(
                "SELECT DATEDIFF('MINUTE', created_at, expire_at) FROM t_inventory_reservation WHERE order_no = 'P1'",
                Long.class);
        assertThat(minutes).isBetween(19L, 20L);
    }

    private List<Integer> bucketStocks() {
        return jdbc.queryForList(
                "SELECT stock FROM t_inventory_bucket WHERE sku_id = 50001 ORDER BY bucket_no", Integer.class);
    }
}
