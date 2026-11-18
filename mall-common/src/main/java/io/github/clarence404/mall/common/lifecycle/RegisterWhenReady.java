package io.github.clarence404.mall.common.lifecycle;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 预热完成再注册：关掉 Nacos 的自动注册（spring.cloud.nacos.discovery.register-enabled: false），
 * 在就绪状态变为 ACCEPTING_TRAFFIC 时再注册。预热放在 ApplicationRunner 里，readiness 和 Nacos 注册都在预热之后。
 *
 * <p>停机时也要自己注销：自动注册关掉以后，Nacos 的自动注销跟着没了。不补这一步，实例要等进程关掉 Nacos 连接才从列表里消失，
 * 那时 Tomcat 已经停了，调用方在自己的实例缓存过期前会一直往这里发请求（高可用实验 C：每次重启丢几百个请求）。
 * 注销放在 ContextClosedEvent 的最前面：它早于 Nacos 的优雅下线等待（10 秒）和 Tomcat 的优雅停机，那 10 秒才等得有意义。
 * Kubernetes 里 preStop 已经调过 /internal/offline，这里再注销一次没有副作用。
 */
@Slf4j
@Component
class RegisterWhenReady {

    private final ObjectProvider<ServiceRegistry<Registration>> serviceRegistry;
    private final ObjectProvider<Registration> registration;
    private final ApplicationContext applicationContext;
    private final boolean deregisterOnShutdown; // 只有高可用实验 C 为了复现修复前的行为才关掉
    private final AtomicBoolean deregistered = new AtomicBoolean();

    RegisterWhenReady(
            ObjectProvider<ServiceRegistry<Registration>> serviceRegistry,
            ObjectProvider<Registration> registration,
            ApplicationContext applicationContext,
            @Value("${mall.lifecycle.deregister-on-shutdown:true}") boolean deregisterOnShutdown) {
        this.serviceRegistry = serviceRegistry;
        this.registration = registration;
        this.applicationContext = applicationContext;
        this.deregisterOnShutdown = deregisterOnShutdown;
    }

    @EventListener
    void onReadiness(AvailabilityChangeEvent<ReadinessState> event) {
        if (event.getState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return;
        }
        ServiceRegistry<Registration> registry = serviceRegistry.getIfAvailable();
        Registration self = registration.getIfAvailable();
        if (registry != null && self != null) {
            registry.register(self); // 预热完成才进入 Nacos，网关才会把流量发过来
            log.info("预热完成，注册到注册中心 {}:{}", self.getHost(), self.getPort());
        }
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void onClose(ContextClosedEvent event) {
        // 管理端口是子上下文，它关闭时事件也会传到这里；只认自己所在的上下文，并且只注销一次
        if (!deregisterOnShutdown
                || event.getApplicationContext() != applicationContext
                || !deregistered.compareAndSet(false, true)) {
            return;
        }
        ServiceRegistry<Registration> registry = serviceRegistry.getIfAvailable();
        Registration self = registration.getIfAvailable();
        if (registry == null || self == null) {
            return;
        }
        try {
            registry.deregister(self);
            log.info("开始停机，先从注册中心注销 {}:{}", self.getHost(), self.getPort());
        } catch (RuntimeException e) {
            log.warn("停机时注销失败，继续停机", e); // 注册中心不可用不能挡住停机
        }
    }
}
