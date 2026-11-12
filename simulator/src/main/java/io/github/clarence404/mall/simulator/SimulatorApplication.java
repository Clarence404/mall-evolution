package io.github.clarence404.mall.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 流量模拟器的入口：一个不开端口的 Spring Boot 应用，启动后由 {@link TrafficSimulator} 起一批虚拟用户，经网关长期模拟真人浏览、登录、下单、支付，
 * 让 Prometheus / Grafana 的面板一直有数据。
 *
 * <p>只用于本地 / 开发环境：用的是演示账号，补货和看库存的接口只在 demo profile 下存在。启动前先起好四个服务（IDEA 里的「全部服务」）。
 * 停止：Ctrl+C 或 IDEA 的停止按钮，虚拟用户做完手上的请求就退出。
 */
@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorApplication {

    /**
     * 启动。
     *
     * @param args 命令行参数，可以用 --simulator.xxx=yyy 覆盖配置
     */
    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
