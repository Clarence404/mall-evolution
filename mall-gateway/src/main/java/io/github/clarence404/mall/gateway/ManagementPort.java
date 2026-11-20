package io.github.clarence404.mall.gateway;

import java.net.InetSocketAddress;
import org.springframework.core.env.Environment;
import org.springframework.web.server.ServerWebExchange;

/**
 * 独立管理端口的端口号，查到一次就记住（和 mall-common 里服务用的那个是同一个做法，网关不依赖 mall-common，各放一份）。
 *
 * <p>管理端口过滤链每个请求都要判断「是不是打在管理端口上」。原先每次判断都去环境里查两个属性，一次查询要把所有配置源翻一遍；
 * 高性能实验 I 的 CPU 采样里，网关各轮有 3%–9% 的采样落在这两次查询上（experiments/i-locate）。端口起来之后不会再变，记住即可。
 */
final class ManagementPort {

    private final Environment env;
    private volatile Integer resolved; // null：还不知道

    ManagementPort(Environment env) {
        this.env = env;
    }

    boolean matches(ServerWebExchange exchange) {
        Integer port = resolved;
        if (port == null) {
            port = GatewaySecurityConfig.resolveManagementPort(env);
            if (port == null) {
                return false; // 服务器还没起完，下次再查
            }
            resolved = port;
        }
        InetSocketAddress local = exchange.getRequest().getLocalAddress();
        return local != null && local.getPort() == port;
    }
}
