package io.github.clarence404.mall.common.remote;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务间 HTTP 调用（HTTP 接口客户端，底层是 Apache HttpClient 5）的连接池参数，三项都可以不配，不配就是库的默认值。
 *
 * <p>为什么要有这几个开关：HttpClient 5 是随 nacos-client 间接引进来的，Spring Boot 检测到它就用它做 RestClient 的底层，
 * 而它的默认连接池是<b>每个目标地址 5 条、总共 25 条</b>，取连接的等待上限是 <b>3 分钟</b>——
 * spring.http.serviceclient.*.read-timeout 管不到「等连接」这一段。下游一变慢，5 条连接全被占住，后面的调用就在池子里排队，
 * 读超时形同虚设。高可用实验 A（experiments/a-slow-downstream）用这几个开关对比；默认值是否调整，等实验数据出来再定。
 *
 * @param maxConnectionsPerRoute 每个目标地址（host:port，即下游的一个实例）最多几条连接；HttpClient 5 默认 5
 * @param maxConnectionsTotal 所有目标加起来最多几条连接；HttpClient 5 默认 25
 * @param connectionRequestTimeout 从池里取连接最多等多久，超过抛异常（RestClient 包成 ResourceAccessException）；HttpClient 5 默认 3 分钟
 */
@ConfigurationProperties(prefix = "mall.http-client")
public record HttpClientPoolProperties(
        Integer maxConnectionsPerRoute, Integer maxConnectionsTotal, Duration connectionRequestTimeout) {

    /** 三项都没配：完全不动 Spring Boot 的默认行为。 */
    boolean isEmpty() {
        return maxConnectionsPerRoute == null && maxConnectionsTotal == null && connectionRequestTimeout == null;
    }
}
