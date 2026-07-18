package com.checkoutvault.checkoutservice.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Produces the single, shared java.net.http.HttpClient instance for the
 * naive version of the service.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient sharedHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    }
}
