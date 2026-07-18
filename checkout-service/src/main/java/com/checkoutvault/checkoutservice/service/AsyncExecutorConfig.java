package com.checkoutvault.checkoutservice.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executor used to run the Inventory call concurrently with the
 * Fraud/Payment chain. A new virtual thread is spawned per task rather
 * than pooling a fixed number of platform threads.
 */
@Configuration
public class AsyncExecutorConfig {

    @Bean
    public ExecutorService checkoutExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
