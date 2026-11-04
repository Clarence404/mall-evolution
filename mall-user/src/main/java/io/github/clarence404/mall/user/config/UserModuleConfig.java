package io.github.clarence404.mall.user.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.clarence404.mall.common.security.JwtProperties;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 每个模块只扫描自己包下的 Mapper；签发令牌只在用户模块。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "io.github.clarence404.mall.user", annotationClass = Mapper.class)
public class UserModuleConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(props.secretKey()));
    }
}
