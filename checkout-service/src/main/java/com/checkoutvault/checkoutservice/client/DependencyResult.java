package com.checkoutvault.checkoutservice.client;

/**
 * Outcome of a single call to a downstream dependency (Fraud, Payment, or
 * Inventory).
 */
public sealed interface DependencyResult {
    record Success(String body) implements DependencyResult {}

    record Failure(String reason) implements DependencyResult {}

    record Timeout() implements DependencyResult {}
}
