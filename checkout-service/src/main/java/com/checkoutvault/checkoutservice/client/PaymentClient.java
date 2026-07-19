package com.checkoutvault.checkoutservice.client;

import com.checkoutvault.checkoutservice.config.CheckoutVaultProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class PaymentClient {

    private final DependencyClient dependencyClient;
    private final CheckoutVaultProperties.DependencyProperties config;

    public PaymentClient(
        @Qualifier("paymentDependencyClient") DependencyClient dependencyClient, 
        CheckoutVaultProperties properties) {
        this.dependencyClient = dependencyClient;
        this.config = properties.dependencies().payment();
    }

    public DependencyResult charge(String orderPayload) {
        return dependencyClient.call(
            config.baseUrl(),
            config.path(),
            orderPayload,
            Duration.ofMillis(config.timeoutMs())
        );
    }
}
