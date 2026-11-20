package io.github.clarence404.mall.common.security;

import org.springframework.core.env.Environment;

/**
 * 独立管理端口的端口号，查到一次就记住。
 *
 * <p>管理端口过滤链的匹配条件是「这个请求是不是打在管理端口上」，每个请求都要判断一次。端口号要等 Web 服务器起来之后才写进环境
 * （local.management.port），所以不能在建过滤链时就取；原先的写法是每次判断都去环境里查两个属性，一次查询要把所有配置源翻一遍。
 * 高性能实验 I 的 CPU 采样里，网关各轮有 3%–9% 的采样落在这两次查询上（experiments/i-locate），服务这边是同一种写法。端口起来之后不会再变，记住即可。
 */
final class ManagementPort {

    private final Environment env;
    private volatile Integer resolved; // null：还不知道

    ManagementPort(Environment env) {
        this.env = env;
    }

    boolean matches(int localPort) {
        Integer port = resolved;
        if (port == null) {
            port = SecurityConfig.resolveManagementPort(env);
            if (port == null) {
                return false; // 服务器还没起完，下次再查
            }
            resolved = port;
        }
        return port == localPort;
    }
}
