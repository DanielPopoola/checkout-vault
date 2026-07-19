package com.checkoutvault.checkoutservice.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.http.HttpTimeoutException;

/**
 * Default DependencyClient implementation, backed by java.net.http.HttpClient.
 *
 * Responsible for translating every possible outcome of a real HTTP call
 * (2xx, 5xx, timeout, connection failure) into a DependencyResult — this
 * is the only place in the codebase that should ever catch an
 * IOException or check an HTTP status code (Rule 3: business logic
 * should not know HTTP framework details).
 *
 * NOT a @Component — instances are constructed explicitly by
 * HttpClientConfig's @Bean methods, one per dependency, each wrapping a
 * different HttpClient (shared in naive mode, physically separate with
 * a dedicated executor in isolated mode). Auto-registering this as a
 * singleton @Component would create a fourth, unwanted instance and
 * conflict with that explicit per-dependency wiring.
 */
public class HttpDependencyClient implements DependencyClient {

    private final HttpClient httpClient;

    public HttpDependencyClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public DependencyResult call(String baseUrl, String path, String requestBody, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json");

        HttpRequest request = requestBody == null || requestBody.isEmpty()
                ? builder.GET().build()
                : builder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new DependencyResult.Success(response.body());
            }
            return new DependencyResult.Failure("HTTP " + response.statusCode());

        } catch (HttpTimeoutException e) {
            // Covers both `slow` mode exceeding our timeout and `hang`
            // mode never responding — java.net.http surfaces both as
            // this exception once the request's own timeout elapses.
            return new DependencyResult.Timeout();

        } catch (IOException e) {
            // Connection refused, DNS failure, etc. — the dependency was
            // unreachable, not slow. Treated as Failure, not Timeout,
            // since retrying instantly wouldn't help either way but the
            // failure mode is conceptually different (nothing to "wait
            // out").
            return new DependencyResult.Failure("connection error: " + e.getMessage());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DependencyResult.Failure("interrupted");
        }
    }
}