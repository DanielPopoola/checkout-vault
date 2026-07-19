package com.checkoutvault.checkoutservice.client;

import com.checkoutvault.checkoutservice.config.CheckoutVaultProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps each dependency's HttpDependencyClient in an
 * IsolatedDependencyClient (semaphore layer) — always, regardless of
 * isolation.mode.
 *
 * Why always, not conditional: the semaphore (concurrency cap) and the
 * underlying HttpClient separation (whether Fraud/Payment/Inventory
 * share one worker pool or each get their own — decided in
 * HttpClientConfig) are two independent concerns. The semaphore being
 * present or absent doesn't change whether the pool is shared; the pool
 * being shared or separate doesn't need the semaphore to also toggle.
 * Making both flip together would mean naive-vs-isolated changes TWO
 * variables at once, making it unclear which one caused any observed
 * difference. Keeping the semaphore always-on isolates the comparison
 * to the one variable that's actually being tested: pool separation.
 * isolation.mode is consulted in exactly one place (HttpClientConfig).
 */
@Configuration
public class DependencyClientConfig {

    @Bean
    public DependencyClient fraudDependencyClient(
            @Qualifier("fraudHttpDependencyClient") HttpDependencyClient fraudHttpDependencyClient,
            CheckoutVaultProperties properties
    ) {
        return new IsolatedDependencyClient(fraudHttpDependencyClient, properties.dependencies().fraud().permits(), "fraud");
    }

    @Bean
    public DependencyClient paymentDependencyClient(
            @Qualifier("paymentHttpDependencyClient") HttpDependencyClient paymentHttpDependencyClient,
            CheckoutVaultProperties properties
    ) {
        return new IsolatedDependencyClient(paymentHttpDependencyClient, properties.dependencies().payment().permits(), "payment");
    }

    @Bean
    public DependencyClient inventoryDependencyClient(
            @Qualifier("inventoryHttpDependencyClient") HttpDependencyClient inventoryHttpDependencyClient,
            CheckoutVaultProperties properties
    ) {
        return new IsolatedDependencyClient(inventoryHttpDependencyClient, properties.dependencies().inventory().permits(), "inventory");
    }
}