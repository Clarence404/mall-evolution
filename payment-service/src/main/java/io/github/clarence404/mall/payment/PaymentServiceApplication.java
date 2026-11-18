package io.github.clarence404.mall.payment;

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
@ImportHttpServices(group = "mall-app", types = OrderQueryApi.class)
public class PaymentServiceApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(PaymentServiceApplication.class, args);
        } catch (RuntimeException e) {
            // 启动失败时 Spring 已经打印原因并关闭了上下文，但启动途中建好的客户端（RocketMQ、Nacos）留有非守护线程，
            // JVM 会一直挂着不退出，Kubernetes 也就不会重启它（高可用实验里两次遇到：启动瞬间连不上 Redis）。显式退出
            System.exit(1);
        }
    }
}
