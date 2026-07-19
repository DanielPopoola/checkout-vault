package com.checkoutvault.checkoutservice.client;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Decorates a DependencyClient with per-instance concurrency limiting.
 */
public class IsolatedDependencyClient implements DependencyClient {

    private final DependencyClient delegate;
    private final Semaphore semaphore;
    private final String dependencyName; // for Failure messages / future metrics tagging

    public IsolatedDependencyClient(DependencyClient delegate, int permits, String dependencyName) {
        this.delegate = delegate;
        this.semaphore = new Semaphore(permits);
        this.dependencyName = dependencyName;
    }

    @Override
    public DependencyResult call(String baseUrl, String path, String requestBody, Duration timeout) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DependencyResult.Failure(dependencyName + ": interrupted while waiting for permit");
        }

        if (!acquired) {
            return new DependencyResult.Failure(dependencyName + ":bulkhead full, no permit available");
        }

        try {
            return delegate.call(baseUrl, path, requestBody, timeout);
        } finally {
            semaphore.release();
        }
    }

    /** Exposed for the Micrometer gauge added in plan step 5/6 (permits currently in use). */
    public int availablePermits() {
        return semaphore.availablePermits();
    }
}
