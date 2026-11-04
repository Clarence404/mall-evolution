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
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
