package com.checkoutvault.checkoutservice.client;

import com.checkoutvault.checkoutservice.config.CheckoutVaultProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class InventoryClient {

    private final DependencyClient dependencyClient;
    private final CheckoutVaultProperties.DependencyProperties config;

    public InventoryClient(DependencyClient dependencyClient, CheckoutVaultProperties properties) {
        this.dependencyClient = dependencyClient;
        this.config = properties.dependencies().inventory();
    }

    /**
     * Checks stock/shipping eligibility
     */
    public DependencyResult checkStock() {
        return dependencyClient.call(
                config.baseUrl(),
                config.path(),
                "",
                Duration.ofMillis(config.timeoutMs())
        );
    }
}
