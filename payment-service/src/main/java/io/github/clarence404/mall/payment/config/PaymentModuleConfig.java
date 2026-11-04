package io.github.clarence404.mall.payment.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 只扫描支付服务自己包下的 Mapper。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "io.github.clarence404.mall.payment", annotationClass = Mapper.class)
public class PaymentModuleConfig {}
