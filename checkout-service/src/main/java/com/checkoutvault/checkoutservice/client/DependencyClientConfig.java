package com.checkoutvault.checkoutservice.client;

import com.checkoutvault.checkoutservice.config.CheckoutVaultProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps each dependency's HttpDependencyClient in an
 * IsolatedDependencyClient (semaphore + metrics layer) — always,
 * regardless of isolation.mode.
 *
 * MeterRegistry is auto-configured by Spring Boot Actuator (backed by
 * the Prometheus registry from the micrometer-registry-prometheus
 * dependency) — no extra wiring needed, just inject it.
 */
@Configuration
public class DependencyClientConfig {

    @Bean
    public DependencyClient fraudDependencyClient(
            @Qualifier("fraudHttpDependencyClient") HttpDependencyClient fraudHttpDependencyClient,
            CheckoutVaultProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new IsolatedDependencyClient(
                fraudHttpDependencyClient,
                properties.dependencies().fraud().permits(),
                "fraud",
                meterRegistry
        );
    }

    @Bean
    public DependencyClient paymentDependencyClient(
            @Qualifier("paymentHttpDependencyClient") HttpDependencyClient paymentHttpDependencyClient,
            CheckoutVaultProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new IsolatedDependencyClient(
                paymentHttpDependencyClient,
                properties.dependencies().payment().permits(),
                "payment",
                meterRegistry
        );
    }

    @Bean
    public DependencyClient inventoryDependencyClient(
            @Qualifier("inventoryHttpDependencyClient") HttpDependencyClient inventoryHttpDependencyClient,
            CheckoutVaultProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new IsolatedDependencyClient(
                inventoryHttpDependencyClient,
                properties.dependencies().inventory().permits(),
                "inventory",
                meterRegistry
        );
    }
}