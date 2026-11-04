package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.clarence404.mall.common.error.BizException;
import io.github.clarence404.mall.order.domain.OrderMapper;
import io.github.clarence404.mall.order.place.AddressForm;
import io.github.clarence404.mall.order.place.OrderLine;
import io.github.clarence404.mall.order.place.PlaceOrderRequest;
import io.github.clarence404.mall.order.place.PlaceOrderResult;
import io.github.clarence404.mall.order.place.PlaceOrderService;
import io.github.clarence404.mall.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 下单 = 扣库存 + 写订单 + 写明细，一个本地事务；uk_user_biz 兜底幂等。 */
class PlaceOrderTxIT extends IntegrationTestBase {

    @Autowired
    PlaceOrderService placeOrderService;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    JdbcTemplate jdbc;

    PlaceOrderRequest request(long skuId, int quantity) {
        return new PlaceOrderRequest(
                List.of(new OrderLine(skuId, quantity)), new AddressForm("张三", "13800000000", "杭州市西湖区"), null);
    }

    int stock(long skuId) {
        return jdbc.queryForObject("SELECT stock FROM t_inventory WHERE sku_id = ?", Integer.class, skuId);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneOrderAndDeductOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        int before = stock(20002L);
        List<Callable<PlaceOrderResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> placeOrderService.place(10001L, key, request(20002L, 1)));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<String> orderNos = new ArrayList<>();
            for (Future<PlaceOrderResult> f : pool.invokeAll(tasks)) {
                orderNos.add(f.get().order().getOrderNo());
            }
            assertThat(orderNos).containsOnly(orderNos.getFirst());
        }
        assertThat(stock(20002L)).isEqualTo(before - 1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_order WHERE user_id = 10001 AND biz_no = ?", Integer.class, key))
                .isEqualTo(1);
    }

    @Test
    void insufficientStockRollsBackWholeTransaction() {
        jdbc.update("UPDATE t_inventory SET stock = 1 WHERE sku_id = 20003");
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> placeOrderService.place(10001L, key, request(20003L, 2)))
                .isInstanceOf(BizException.class);

        assertThat(stock(20003L)).isEqualTo(1);
        assertThat(orderMapper.selectByUserAndBizNo(10001L, key)).isNull();
    }

    @Test
    void noOversellUnderConcurrency() throws Exception {
        jdbc.update("UPDATE t_inventory SET stock = 5 WHERE sku_id = 20001");
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long userId = 20000L + i;
            tasks.add(() -> {
                try {
                    placeOrderService.place(userId, UUID.randomUUID().toString(), request(20001L, 1));
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
        assertThat(stock(20001L)).isZero();
    }
}
