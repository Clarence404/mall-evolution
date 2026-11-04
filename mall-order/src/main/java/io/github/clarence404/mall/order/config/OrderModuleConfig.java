package io.github.clarence404.mall.order.config;

import io.github.clarence404.mall.order.domain.OrderStatus;
import io.github.clarence404.mall.order.domain.OrderStatusTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 每个模块只扫描自己包下的 Mapper。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "io.github.clarence404.mall.order", annotationClass = Mapper.class)
public class OrderModuleConfig {

    @Bean
    ConfigurationCustomizer orderStatusTypeHandler() {
        return configuration ->
                configuration.getTypeHandlerRegistry().register(OrderStatus.class, new OrderStatusTypeHandler());
    }
}
