package io.github.clarence404.mall.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

import io.github.clarence404.mall.common.remote.RemoteBizException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 拆分过渡期的下单在真实 MySQL 上：并发同键只会有一笔订单，输掉的请求把远程扣掉的库存还回去。需要 Docker。
 *
 * <p>库存不超卖的并发测试随库存服务迁到 inventory-service 的 InventoryDeductIT。
 */
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

    @Test
    void concurrentRequestsWithSameKeyCreateOneOrderAndReturnLosersStock() throws Exception {
        String key = UUID.randomUUID().toString();
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
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_order WHERE user_id = 10001 AND biz_no = ?", Integer.class, key))
                .isEqualTo(1);
        long deducts = count("deduct");
        long restores = count("restore");
        assertThat(deducts - restores).isEqualTo(1); // 每个撞唯一键的请求都把自己扣的库存还了回去
    }

    @Test
    void stockInsufficientFromInventoryServiceCreatesNoOrder() {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "库存不足");
        pd.setProperty("code", "STOCK_INSUFFICIENT");
        doThrow(new RemoteBizException(HttpStatus.CONFLICT, pd))
                .when(inventoryApi)
                .deduct(any());
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> placeOrderService.place(10001L, key, request(20003L, 2)))
                .isInstanceOf(RemoteBizException.class);
        assertThat(orderMapper.selectByUserAndBizNo(10001L, key)).isNull();
    }

    private long count(String method) {
        return mockingDetails(inventoryApi).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(method))
                .count();
    }
}
