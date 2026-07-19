package com.checkoutvault.checkoutservice.client;

import com.checkoutvault.checkoutservice.config.CheckoutVaultProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Produces the HttpDependencyClient instance(s) backing each dependency's
 * outbound HTTP calls, wired according to isolation.mode.
 *
 * naive:    ONE HttpClient (default, unbounded internal executor) is
 *           shared across Fraud, Payment, and Inventory — reproducing
 *           the bug proven in plan steps 2-4: a hung Inventory call can
 *           occupy a worker thread that Fraud/Payment's calls needed.
 *
 * isolated: THREE separate HttpClients, each given its own small,
 *           explicitly-sized fixed thread pool (via .executor(...)) —
 *           sized close to that dependency's own semaphore permit
 *           count, not the ~20-ish default. This is the actual fix for
 *           the naive bug: physical separation, so a hung Inventory
 *           call can never occupy a thread Fraud/Payment's calls need,
 *           because they no longer share any thread pool at all.
 *           IsolatedDependencyClient (added in DependencyClientConfig)
 *           layers the semaphore + fail-fast-on-full behavior on top of
 *           this physical separation — the two are complementary, not
 *           redundant (see session notes: unbounded queuing behind a
 *           merely-separate-but-unbounded executor risks OOM under
 *           sustained load; the semaphore is what prevents that).
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpDependencyClient fraudHttpDependencyClient(CheckoutVaultProperties properties) {
        return build(properties, properties.dependencies().fraud().permits());
    }

    @Bean
    public HttpDependencyClient paymentHttpDependencyClient(CheckoutVaultProperties properties) {
        return build(properties, properties.dependencies().payment().permits());
    }

    @Bean
    public HttpDependencyClient inventoryHttpDependencyClient(CheckoutVaultProperties properties) {
        return build(properties, properties.dependencies().inventory().permits());
    }

    private HttpDependencyClient build(CheckoutVaultProperties properties, int permits) {
        boolean isolated = properties.isolation().mode() == CheckoutVaultProperties.IsolationProperties.Mode.ISOLATED;

        if (!isolated) {
            // Naive: every call to this method still shares the SAME
            // underlying static default HttpClient instance — see
            // sharedNaiveHttpClient() below. Each dependency still gets
            // its own HttpDependencyClient object, but they all wrap
            // the one shared HttpClient, faithfully reproducing the
            // naive bug.
            return new HttpDependencyClient(sharedNaiveHttpClient);
        }

        // Isolated: a dedicated HttpClient with its own small executor,
        // sized close to this dependency's own permit count. Not
        // exactly equal to permits (a little headroom is reasonable —
        // e.g. permits+2 — since the executor also handles some
        // internal HttpClient bookkeeping, not purely request threads),
        // but nowhere near the ~20-ish shared default.
        ExecutorService dedicatedExecutor = Executors.newFixedThreadPool(permits + 2);
        HttpClient dedicatedClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(dedicatedExecutor)
                .build();
        return new HttpDependencyClient(dedicatedClient);
    }

    // Constructed once, lazily, the first time naive mode needs it —
    // NOT a @Bean itself, since we don't want Spring creating this
    // eagerly in isolated mode where it's never used.
    private final HttpClient sharedNaiveHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
}