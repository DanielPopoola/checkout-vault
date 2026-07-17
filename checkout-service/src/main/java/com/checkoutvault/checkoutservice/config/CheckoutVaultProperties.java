package com.checkoutvault.checkoutservice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "checkout-vault")
@Validated
public record CheckoutVaultProperties(
    @Valid @NotNull IsolationProperties isolation,
    @Valid @NotNull DependenciesProperties dependencies
) {
    public record IsolationProperties(@NotNull Mode mode) {
        public enum Mode {
            NAIVE,
            ISOLATED,
        }
    }

    public record DependenciesProperties(
        @Valid @NotNull DependencyProperties fraud,
        @Valid @NotNull DependencyProperties payment,
        @Valid @NotNull DependencyProperties inventory
    ) {}

    /**
     * Config for a single downstream dependency (Fraud, Payment, or
     * Inventory). Same shape for all three — the values differ, the
     * structure doesn't.
     */
    public record DependencyProperties(
        @NotNull String baseUrl,
        @NotNull String path,
        @Min(1) int permits,
        @Min(1) long timeoutMs
    ) {}
}
