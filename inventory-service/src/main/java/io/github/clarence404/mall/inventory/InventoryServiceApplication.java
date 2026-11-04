package io.github.clarence404.mall.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 库存服务：从 mall-app 拆出，独立部署、独立扩容，只连自己的 mall_inventory 库。 */
@SpringBootApplication(scanBasePackages = "io.github.clarence404.mall")
@EnableScheduling
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
