package io.github.clarence404.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 管理端口过滤链只匹配打在独立管理端口上的请求；没开独立管理端口（两个端口相同或没有）时一律不匹配。 */
class ManagementPortMatchTest {

    @Test
    void matchesOnlyRequestsOnSeparateManagementPort() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("local.server.port", "8082")
                .withProperty("local.management.port", "18082");
        assertThat(SecurityConfig.onManagementPort(env, 18082)).isTrue();
        assertThat(SecurityConfig.onManagementPort(env, 8082)).isFalse();
    }

    @Test
    void neverMatchesWithoutSeparateManagementPort() {
        // 没有独立管理端口时，Spring Boot 让 local.management.port 等于 local.server.port
        MockEnvironment samePort = new MockEnvironment()
                .withProperty("local.server.port", "8080")
                .withProperty("local.management.port", "8080");
        assertThat(SecurityConfig.onManagementPort(samePort, 8080)).isFalse();
        assertThat(SecurityConfig.onManagementPort(new MockEnvironment(), 8080)).isFalse();
    }

    @Test
    void remembersThePortOnceBothPortsAreKnown() {
        MockEnvironment env = new MockEnvironment();
        ManagementPort port = new ManagementPort(env);
        assertThat(port.matches(18082)).isFalse(); // 服务器还没起完：不匹配，也不记结果

        env.setProperty("local.server.port", "8082");
        env.setProperty("local.management.port", "18082");
        assertThat(port.matches(18082)).isTrue();
        assertThat(port.matches(8082)).isFalse();

        env.setProperty("local.management.port", "19999"); // 之后不再查环境
        assertThat(port.matches(18082)).isTrue();
        assertThat(port.matches(19999)).isFalse();
    }
}
