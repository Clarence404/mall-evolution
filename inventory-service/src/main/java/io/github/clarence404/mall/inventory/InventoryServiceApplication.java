package io.github.clarence404.mall.inventory;

import io.github.clarence404.mall.order.api.OrderQueryApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.service.registry.ImportHttpServices;

/** 库存服务：从 mall-app 拆出，独立部署、独立扩容，只连自己的 mall_inventory 库。 */
@SpringBootApplication(scanBasePackages = "io.github.clarence404.mall")
@EnableScheduling
@ImportHttpServices(group = "mall-app", types = OrderQueryApi.class) // 只在预占过期回查里反向查订单
public class InventoryServiceApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(InventoryServiceApplication.class, args);
        } catch (RuntimeException e) {
            // 启动失败时 Spring 已经打印原因并关闭了上下文，但启动途中建好的客户端（RocketMQ、Nacos）留有非守护线程，
            // JVM 会一直挂着不退出，Kubernetes 也就不会重启它（高可用实验里两次遇到：启动瞬间连不上 Redis）。显式退出
            System.exit(1);
        }
    }
}
