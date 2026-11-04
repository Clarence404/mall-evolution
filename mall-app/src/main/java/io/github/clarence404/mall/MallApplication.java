package io.github.clarence404.mall;

import io.github.clarence404.mall.inventory.api.InventoryApi;
import io.github.clarence404.mall.payment.api.PaymentApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * 启动类放在最顶层包，组件扫描覆盖订单、商品、用户模块。阶段三起库存与支付拆成独立服务：
 * 按服务名注册 HTTP 接口客户端组，组名就是 Nacos 里的服务名，LoadBalancer 自动接入。
 */
@SpringBootApplication
@EnableScheduling
@EnableResilientMethods // 阶段四：只读查询用 Spring Framework 7 自带的 @Retryable
@ImportHttpServices(group = "inventory-service", types = InventoryApi.class)
@ImportHttpServices(group = "payment-service", types = PaymentApi.class)
public class MallApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallApplication.class, args);
    }
}
