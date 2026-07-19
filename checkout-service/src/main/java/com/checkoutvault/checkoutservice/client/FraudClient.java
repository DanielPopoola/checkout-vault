package com.checkoutvault.checkoutservice.client;

import com.checkoutvault.checkoutservice.config.CheckoutVaultProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;



@Component
public class FraudClient {

    private final DependencyClient dependencyClient;
    private final CheckoutVaultProperties.DependencyProperties config;

    public FraudClient(
        @Qualifier("fraudDependencyClient") DependencyClient dependencyClient, 
        CheckoutVaultProperties properties
    ) {
        this.dependencyClient = dependencyClient;
        this.config = properties.dependencies().fraud();
    }

    /**
     * Requests a fraud risk decision for the given order payload.
     */
    public DependencyResult score(String orderPayload) {
        return dependencyClient.call(
                config.baseUrl(),
                config.path(),
                orderPayload,
                Duration.ofMillis(config.timeoutMs())
        );
    }
}