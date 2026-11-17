package io.github.clarence404.mall.common.remote;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link HttpClientPoolProperties} 应用到 Spring Boot 构建 HttpClient 5 的过程上：HTTP 接口客户端组（inventory-service、
 * payment-service、mall-app）都由同一个 ClientHttpRequestFactoryBuilder 构建，这里给它加一个定制器。
 *
 * <p>三项都没配时定制器什么也不做，行为与不加这个类完全一样。只在类路径上有 HttpClient 5 和 Spring Boot 的 HTTP 客户端模块时生效
 * （mall-common 对这两个是可选依赖，三个服务都经 nacos-client、spring-boot-starter-restclient 带着它们）。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(
        name = {
            "org.apache.hc.client5.http.impl.classic.HttpClientBuilder",
            "org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer"
        })
@EnableConfigurationProperties(HttpClientPoolProperties.class)
public class HttpClientPoolConfig {

    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder> httpClientPoolCustomizer(
            HttpClientPoolProperties props) {
        return builder -> apply(builder, props);
    }

    static HttpComponentsClientHttpRequestFactoryBuilder apply(
            HttpComponentsClientHttpRequestFactoryBuilder builder, HttpClientPoolProperties props) {
        if (props.isEmpty()) {
            return builder;
        }
        log.info(
                "服务间 HTTP 连接池：每个目标 {} 条、总共 {} 条、取连接最多等 {}（未配置的项保持 HttpClient 5 默认值）",
                props.maxConnectionsPerRoute(),
                props.maxConnectionsTotal(),
                props.connectionRequestTimeout());
        HttpComponentsClientHttpRequestFactoryBuilder b = builder.withConnectionManagerCustomizer(pool -> {
            if (props.maxConnectionsPerRoute() != null) {
                pool.setMaxConnPerRoute(props.maxConnectionsPerRoute());
            }
            if (props.maxConnectionsTotal() != null) {
                pool.setMaxConnTotal(props.maxConnectionsTotal());
            }
        });
        if (props.connectionRequestTimeout() != null) {
            long millis = props.connectionRequestTimeout().toMillis();
            b = b.withDefaultRequestConfigCustomizer(
                    rc -> rc.setConnectionRequestTimeout(Timeout.of(millis, TimeUnit.MILLISECONDS)));
        }
        return b;
    }
}
