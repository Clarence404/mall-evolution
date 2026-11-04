package io.github.clarence404.mall.support;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.payment.api.PaymentApi;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 不需要 Docker 的全上下文测试基类：H2（MySQL 兼容模式）+ Flyway + MockMvc。
 *
 * <p>阶段三起库存与支付是独立服务，这里用替身代替 HTTP 接口客户端。只验证装配与 SQL 能跑通；
 * 并发、锁、隔离级别相关的行为以 Testcontainers 上的 MySQL 测试（*IT、*ApiTests）为准。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2"})
public abstract class H2TestBase {

    @MockitoBean
    protected InventoryApi inventoryApi;

    @MockitoBean
    protected PaymentApi paymentApi;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** 阶段四有下单频控（同一 SKU 待支付最多 2 单）：每个测试开始前把遗留的待支付订单关掉，测试之间互不影响。 */
    @BeforeEach
    void closeLeftoverPendingOrders() {
        jdbcTemplate.update("UPDATE t_order SET status = 50 WHERE status = 10");
    }
}
