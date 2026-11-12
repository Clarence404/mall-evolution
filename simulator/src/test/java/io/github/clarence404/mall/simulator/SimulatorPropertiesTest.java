package io.github.clarence404.mall.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** 不启动容器（一启动就会开始发请求），只把 application.yml 绑定到 SimulatorProperties，确认默认配置能用、取值克制。 */
class SimulatorPropertiesTest {

    @Test
    void defaultsBindAndAreGentle() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> yml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        yml.forEach(env.getPropertySources()::addLast);
        SimulatorProperties p =
                Binder.get(env).bind("simulator", SimulatorProperties.class).get();

        assertThat(p.accounts()).hasSize(8).allMatch(a -> a.startsWith("138000000"));
        assertThat(p.behaviors()).containsKeys(SimulatorProperties.Behavior.values()); // my-orders → MY_ORDERS
        assertThat(p.curve().hourly()).hasSize(24);
        new LoadCurve(p.curve().hourly(), p.curve().minFactor()); // 配置合法
        assertThat(p.browseSkus()).containsKey(20004L);
        assertThat(p.purchase().skus()).doesNotContainKey(20004L); // 已下架的不买
        assertThat(p.duration()).isEqualTo(Duration.ZERO);
        // 克制：异常请求合计不超过 5%，高峰平均几小时一次
        assertThat(p.anomalies().wrongPassword()
                        + p.anomalies().badRequest()
                        + p.anomalies().notFound())
                .isLessThanOrEqualTo(0.05);
        assertThat(p.spike().meanInterval()).isGreaterThanOrEqualTo(Duration.ofHours(1));
        assertThat(p.stock().restockTo()).isGreaterThan(p.stock().lowWatermark());
    }
}
