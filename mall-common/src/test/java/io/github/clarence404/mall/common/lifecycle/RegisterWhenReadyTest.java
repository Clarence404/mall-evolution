package io.github.clarence404.mall.common.lifecycle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.cloud.client.serviceregistry.Registration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;

class RegisterWhenReadyTest {

    @SuppressWarnings("unchecked")
    ServiceRegistry<Registration> registry = mock(ServiceRegistry.class);

    Registration registration = mock(Registration.class);

    @Test
    void registersOnlyWhenAcceptingTraffic() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("registry", registry);
        beans.addBean("registration", registration);
        RegisterWhenReady listener = new RegisterWhenReady(
                beans.getBeanProvider(org.springframework.core.ResolvableType.forClassWithGenerics(
                        ServiceRegistry.class, Registration.class)),
                beans.getBeanProvider(Registration.class));

        listener.onReadiness(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        verify(registry, never()).register(any());

        listener.onReadiness(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        verify(registry).register(registration);
    }
}
