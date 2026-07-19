package com.checkoutvault.checkoutservice.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Decorates a DependencyClient with per-instance concurrency limiting
 * AND Micrometer instrumentation.
 

 */
public class IsolatedDependencyClient implements DependencyClient {

    private final DependencyClient delegate;
    private final Semaphore semaphore;
    private final String dependencyName;
    private final MeterRegistry meterRegistry;
    private final Timer callTimer;

    public IsolatedDependencyClient(
            DependencyClient delegate,
            int permits,
            String dependencyName,
            MeterRegistry meterRegistry
    ) {
        this.delegate = delegate;
        this.semaphore = new Semaphore(permits);
        this.dependencyName = dependencyName;
        this.meterRegistry = meterRegistry;

        this.callTimer = Timer.builder("checkout.dependency.latency")
                .tag("dependency", dependencyName)
                .publishPercentiles(0.5, 0.99)
                .register(meterRegistry);

        meterRegistry.gauge(
                "checkout.dependency.permits.available",
                io.micrometer.core.instrument.Tags.of("dependency", dependencyName),
                semaphore,
                Semaphore::availablePermits
        );
    }

    @Override
    public DependencyResult call(String baseUrl, String path, String requestBody, Duration timeout) {
        long start = System.nanoTime();
        try {
            DependencyResult result = doCall(baseUrl, path, requestBody, timeout);
            recordOutcome(result);
            return result;
        } finally {
            callTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private DependencyResult doCall(String baseUrl, String path, String requestBody, Duration timeout) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DependencyResult.Failure(dependencyName + ": interrupted while waiting for permit");
        }

        if (!acquired) {
            meterRegistry.counter("checkout.dependency.calls", "dependency", dependencyName, "outcome", "bulkhead_full").increment();
            return new DependencyResult.Failure(dependencyName + ": bulkhead full, no permit available");
        }

        try {
            return delegate.call(baseUrl, path, requestBody, timeout);
        } finally {
            semaphore.release();
        }
    }

    private void recordOutcome(DependencyResult result) {
        String outcome = switch (result) {
            case DependencyResult.Success s -> "success";
            case DependencyResult.Failure f -> f.reason().contains("bulkhead full") ? "bulkhead_full" : "failure";
            case DependencyResult.Timeout t -> "timeout";
        };
        
        if (!outcome.equals("bulkhead_full")) {
            meterRegistry.counter("checkout.dependency.calls", "dependency", dependencyName, "outcome", outcome).increment();
        }
    }

    /** Exposed for tests / diagnostics; the live gauge is registered directly in the constructor. */
    public int availablePermits() {
        return semaphore.availablePermits();
    }
}