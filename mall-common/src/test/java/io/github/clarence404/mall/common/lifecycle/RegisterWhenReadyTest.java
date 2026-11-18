package io.github.clarence404.mall.common.lifecycle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.ResolvableType;

class RegisterWhenReadyTest {

    @SuppressWarnings("unchecked")
    ServiceRegistry<Registration> registry = mock(ServiceRegistry.class);

    Registration registration = mock(Registration.class);

    ApplicationContext context = mock(ApplicationContext.class);

    RegisterWhenReady listener = newListener(true);

    private RegisterWhenReady newListener(boolean deregisterOnShutdown) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("registry", registry);
        beans.addBean("registration", registration);
        return new RegisterWhenReady(
                beans.getBeanProvider(ResolvableType.forClassWithGenerics(ServiceRegistry.class, Registration.class)),
                beans.getBeanProvider(Registration.class),
                context,
                deregisterOnShutdown);
    }

    @Test
    void registersOnlyWhenAcceptingTraffic() {
        listener.onReadiness(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        verify(registry, never()).register(any());

        listener.onReadiness(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        verify(registry).register(registration);
    }

    /** 自动注册关掉后 Nacos 不会替我们注销：停机一开始就要自己注销，而且只注销一次。 */
    @Test
    void deregistersOnceWhenOwnContextCloses() {
        listener.onClose(new ContextClosedEvent(context));
        listener.onClose(new ContextClosedEvent(context));

        verify(registry, times(1)).deregister(registration);
    }

    /** 管理端口是子上下文，它的关闭事件会传到父上下文的监听器；那不是本服务在停机。 */
    @Test
    void ignoresCloseOfAnotherContext() {
        listener.onClose(new ContextClosedEvent(mock(ApplicationContext.class)));

        verify(registry, never()).deregister(any());
    }

    /** 开关关掉时（实验复现修复前的行为）不注销。 */
    @Test
    void doesNotDeregisterWhenSwitchedOff() {
        newListener(false).onClose(new ContextClosedEvent(context));

        verify(registry, never()).deregister(any());
    }

    /** 注册中心不可用时注销会抛异常，不能因此挡住停机。 */
    @Test
    void deregisterFailureDoesNotBlockShutdown() {
        doThrow(new IllegalStateException("nacos down")).when(registry).deregister(registration);

        listener.onClose(new ContextClosedEvent(context));

        verify(registry).deregister(registration);
    }
}
