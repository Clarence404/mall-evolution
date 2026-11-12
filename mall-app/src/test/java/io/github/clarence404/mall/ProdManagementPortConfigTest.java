package io.github.clarence404.mall;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * prod profile 开了独立管理端口：业务端口 8080、管理端口 18080（Kubernetes 的探针与 PodMonitor 都指向容器端口名 management）。
 * 按 Spring Boot 的覆盖顺序叠加 application.yml 与 application-prod.yml，确认两个端口确实不同；
 * 管理端口上能匿名抓指标、业务端口上仍 401 的行为由随机端口的安全测试覆盖。
 */
class ProdManagementPortConfigTest {

    @Test
    void prodProfileSeparatesManagementPort() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        StandardEnvironment env = new StandardEnvironment();
        loader.load("application", new ClassPathResource("application.yml")).forEach(env.getPropertySources()::addLast);
        loader.load("application-prod", new ClassPathResource("application-prod.yml"))
                .forEach(env.getPropertySources()::addFirst); // profile 配置优先
        assertThat(env.getProperty("server.port", Integer.class)).isEqualTo(8080);
        assertThat(env.getProperty("management.server.port", Integer.class)).isEqualTo(18080);
    }
}
