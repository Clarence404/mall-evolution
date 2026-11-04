package io.github.clarence404.mall.support;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 不需要 Docker 的全上下文测试基类：H2（MySQL 兼容模式）+ Flyway + MockMvc。
 *
 * <p>阶段三起库存是独立服务，这里用替身代替 HTTP 接口客户端。只验证装配与 SQL 能跑通；
 * 并发、锁、隔离级别相关的行为以 Testcontainers 上的 MySQL 测试（*IT、*ApiTests）为准。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"it", "h2"})
public abstract class H2TestBase {

    @MockitoBean
    protected InventoryApi inventoryApi;
}
