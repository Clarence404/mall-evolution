package io.github.clarence404.mall.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.clarence404.mall.order.api.OrderQueryApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 预热完成再注册，在真实 Nacos 3.1 上验证：关掉自动注册后由 RegisterWhenReady 在 ACCEPTING_TRAFFIC 时注册，
 * 注册上去的端口必须是实际监听端口（spring.cloud.nacos.discovery.port: ${server.port}），不能是 -1。需要 Docker。
 *
 * <p>和生产一样用固定端口启动（取一个空闲端口当 server.port），注册端口来自 ${server.port}。数据库用 H2；
 * Nacos 客户端走 gRPC（HTTP 端口 + 1000），容器端口是随机映射的，按映射结果设置 gRPC 端口偏移。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
            "spring.cloud.nacos.discovery.enabled=true",
            // 和生产一致：注册相关的 Bean 照常装配（it profile 默认关掉了），只是 register-enabled: false 不自动注册
            "spring.cloud.service-registry.auto-registration.enabled=true",
            "spring.cloud.nacos.discovery.namespace=",
            "spring.cloud.nacos.discovery.ip=127.0.0.1"
        })
@ActiveProfiles({"it", "h2"})
@Testcontainers(disabledWithoutDocker = true)
class NacosRegistrationIT {

    static final GenericContainer<?> NACOS = new GenericContainer<>("nacos/nacos-server:v3.1.1")
            .withEnv("MODE", "standalone")
            .withEnv("NACOS_AUTH_ENABLE", "false")
            .withEnv("NACOS_AUTH_TOKEN", "bWFsbC10ZXN0LW9ubHktbmFjb3MtdG9rZW4tMDEyMzQ1Njc4OQ==")
            .withEnv("NACOS_AUTH_IDENTITY_KEY", "mall-test")
            .withEnv("NACOS_AUTH_IDENTITY_VALUE", "mall-test")
            .withEnv("JVM_XMS", "256m")
            .withEnv("JVM_XMX", "512m")
            .withEnv("JVM_XMN", "128m")
            .withExposedPorts(8848, 9848)
            .waitingFor(Wait.forLogMessage(".*Nacos Server API started successfully.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static final int SERVER_PORT = freePort();

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            NACOS.start();
            // 客户端按「HTTP 端口 + 偏移」连 gRPC；两个端口都是随机映射，偏移按映射后的差值算
            System.setProperty(
                    "nacos.server.grpc.port.offset",
                    String.valueOf(NACOS.getMappedPort(9848) - NACOS.getMappedPort(8848)));
        }
    }

    @DynamicPropertySource
    static void nacos(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.nacos.server-addr", () -> NACOS.getHost() + ":" + NACOS.getMappedPort(8848));
        registry.add("server.port", () -> SERVER_PORT);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @MockitoBean
    OrderQueryApi orderQueryApi;

    @Autowired
    DiscoveryClient discoveryClient;

    @Value("${local.server.port}")
    int port;

    @Test
    void registersAfterReadyWithActualServerPort() throws Exception {
        List<ServiceInstance> instances = List.of();
        for (int i = 0; i < 60 && instances.isEmpty(); i++) { // 注册是异步的，最多等 30 秒
            instances = discoveryClient.getInstances("inventory-service");
            if (instances.isEmpty()) {
                Thread.sleep(500);
            }
        }

        assertThat(instances).singleElement().satisfies(instance -> {
            assertThat(port).isEqualTo(SERVER_PORT);
            assertThat(instance.getPort()).isEqualTo(port).isNotEqualTo(-1);
            assertThat(instance.getHost()).isEqualTo("127.0.0.1");
        });
    }
}
