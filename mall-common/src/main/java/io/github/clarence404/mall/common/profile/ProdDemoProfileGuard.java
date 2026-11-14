package io.github.clarence404.mall.common.profile;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 启动检查：prod 与 demo 两个 profile 不能同时激活，同时激活就让启动失败。
 *
 * <p>demo 只用于演示：打开演示控制台与故障注入接口、把支付超时缩到 1 分钟、熔断改成几次调用就打开、响应头里带缓存来源与 traceId。
 * 这些一旦叠到生产配置上（比如部署时把 SPRING_PROFILES_ACTIVE 写成了 prod,demo），后果是订单 1 分钟就被关掉、
 * 任何登录用户都能给库存服务注入延迟。与其指望没人写错，不如启动时直接拒绝。
 *
 * <p>做法：这个配置类只在两个 profile 同时激活时才生效，一生效就抛异常，Spring 容器创建失败、进程退出。
 * mall-app、inventory-service、payment-service 都会扫描到它；网关不依赖 mall-common，在自己的包里有一份同样的检查。
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod & demo")
public class ProdDemoProfileGuard {

    public static final String MESSAGE = "prod 与 demo 两个 profile 不能同时激活：demo 只用于演示（演示控制台、故障注入、1 分钟支付超时、易触发的熔断），"
            + "请检查 SPRING_PROFILES_ACTIVE / spring.profiles.active，生产环境只保留 prod";

    public ProdDemoProfileGuard() {
        throw new IllegalStateException(MESSAGE);
    }
}
