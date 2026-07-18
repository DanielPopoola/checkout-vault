package com.checkoutvault.checkoutservice.client;

import java.time.Duration;

/**
 * Makes a single bounded HTTP call to a downstream dependency.
 *
 */
public interface DependencyClient {
    DependencyResult call(
        String baseUrl,
        String path,
        String requestBody,
        Duration timeout
    );
}
