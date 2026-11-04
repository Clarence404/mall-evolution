package io.github.clarence404.mall.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 集成测试基类：Testcontainers 拉起真实的 MySQL 8.4，Flyway 建表。没有 Docker 的机器上自动跳过。
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

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            MYSQL.start();
        }
    }
}
