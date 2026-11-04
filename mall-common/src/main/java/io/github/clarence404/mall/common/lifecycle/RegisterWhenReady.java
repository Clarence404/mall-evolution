package io.github.clarence404.mall.common.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 预热完成再注册：关掉 Nacos 的自动注册（spring.cloud.nacos.discovery.register-enabled: false），
 * 在就绪状态变为 ACCEPTING_TRAFFIC 时再注册。预热放在 ApplicationRunner 里，readiness 和 Nacos 注册都在预热之后。
 */
@Slf4j
@Component
class RegisterWhenReady {

    private final ObjectProvider<ServiceRegistry<Registration>> serviceRegistry;
    private final ObjectProvider<Registration> registration;

    RegisterWhenReady(
            ObjectProvider<ServiceRegistry<Registration>> serviceRegistry, ObjectProvider<Registration> registration) {
        this.serviceRegistry = serviceRegistry;
        this.registration = registration;
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
}
