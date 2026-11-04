package io.github.clarence404.mall.common.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 停机第一步：Kubernetes 的 preStop 调用它，先从 Nacos 注销，再 sleep 等调用方的实例缓存刷新。
 *
 * <p>服务间调用走的是 Nacos 里的 Pod IP，不经过 Kubernetes Service，readiness 失败挡不住这部分流量，必须主动注销。
 * 只在集群内可达（/internal/**，网关不路由）。
 */
@Slf4j
@RestController
public class OfflineController {

    private final ObjectProvider<ServiceRegistry<Registration>> serviceRegistry;
    private final ObjectProvider<Registration> registration;

    public OfflineController(
            ObjectProvider<ServiceRegistry<Registration>> serviceRegistry, ObjectProvider<Registration> registration) {
        this.serviceRegistry = serviceRegistry;
        this.registration = registration;
    }

    @PostMapping("/internal/offline")
    public ResponseEntity<Void> offline() {
        ServiceRegistry<Registration> registry = serviceRegistry.getIfAvailable();
        Registration self = registration.getIfAvailable();
        if (registry != null && self != null) {
            registry.deregister(self);
            log.info("已从注册中心注销 {}:{}", self.getHost(), self.getPort());
        }
        return ResponseEntity.noContent().build();
    }
}
