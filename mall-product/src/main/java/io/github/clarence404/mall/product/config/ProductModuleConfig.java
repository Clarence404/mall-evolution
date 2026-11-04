package io.github.clarence404.mall.product.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 每个模块只扫描自己包下的 Mapper。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "io.github.clarence404.mall.product", annotationClass = Mapper.class)
public class ProductModuleConfig {}
