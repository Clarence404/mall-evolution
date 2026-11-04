package io.github.clarence404.mall.support;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 集成测试基类：Testcontainers 拉起真实的 MySQL 8.4 与 Redis 8，Flyway 建表。没有 Docker 的机器上自动跳过。
 *
 * <p>阶段三起库存是独立服务（有自己的集成测试），这里用替身代替 HTTP 接口客户端。
 *
 * <p>容器在整个测试 JVM 里只起一次，不用 {@code @Container}：后者在每个测试类结束时停掉容器，
 * 而 Spring 会跨测试类缓存上下文，下一个类拿到的连接池还指向已经停掉的容器。退出时由 Ryuk 回收。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("mall");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            MYSQL.start();
            REDIS.start();
        }
    }

    @MockitoBean
    protected InventoryApi inventoryApi;
}
