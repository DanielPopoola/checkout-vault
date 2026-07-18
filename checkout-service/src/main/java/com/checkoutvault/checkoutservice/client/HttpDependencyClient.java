package com.checkoutvault.checkoutservice.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.stereotype.Component;


@Component
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
            return new DependencyResult.Failure("HTTP" + response.statusCode());
        } catch (HttpTimeoutException e) {
            return new DependencyResult.Timeout();
        } catch (IOException e) {
            return new DependencyResult.Failure("connection error" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DependencyResult.Failure("interrupted");
        }
    }
}