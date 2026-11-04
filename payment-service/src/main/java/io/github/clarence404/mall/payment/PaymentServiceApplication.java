package io.github.clarence404.mall.payment;

import io.github.clarence404.mall.order.api.OrderPayApi;
import io.github.clarence404.mall.order.api.OrderQueryApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * 支付服务：对接外部渠道、商户密钥隔离，渠道变慢不再拖住订单线程。按服务名 mall-app 注册客户端组，LoadBalancer 自动接入。
 */
@SpringBootApplication(scanBasePackages = "io.github.clarence404.mall")
@EnableScheduling
@ImportHttpServices(
        group = "mall-app",
        types = {OrderQueryApi.class, OrderPayApi.class})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
