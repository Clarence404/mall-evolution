package io.github.clarence404.mall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 入口网关（Spring Cloud Gateway WebFlux 版）：统一 JWT 校验、按路径路由、灰度切流。 */
@SpringBootApplication
public class MallGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallGatewayApplication.class, args);
    }
}
