package io.github.clarence404.mall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 入口网关（Spring Cloud Gateway WebFlux 版）：统一 JWT 校验、按路径路由、灰度切流。 */
@SpringBootApplication
public class MallGatewayApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(MallGatewayApplication.class, args);
        } catch (RuntimeException e) {
            // 启动失败时 Spring 已经打印原因并关闭了上下文，但启动途中建好的客户端（RocketMQ、Nacos）留有非守护线程，
            // JVM 会一直挂着不退出，Kubernetes 也就不会重启它（高可用实验里两次遇到：启动瞬间连不上 Redis）。显式退出
            System.exit(1);
        }
    }
}
