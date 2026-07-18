package com.checkoutvault.checkoutservice.service;

import com.checkoutvault.checkoutservice.client.DependencyResult;
import com.checkoutvault.checkoutservice.client.FraudClient;
import com.checkoutvault.checkoutservice.client.InventoryClient;
import com.checkoutvault.checkoutservice.client.PaymentClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a single checkout request(NAIVE version)
 *
 */
@Service
public class CheckoutService {

    private final FraudClient fraudClient;
    private final PaymentClient paymentClient;
    private final InventoryClient inventoryClient;
    private final ExecutorService checkoutExecutor;

    public CheckoutService(
        FraudClient fraudClient,
        PaymentClient paymentClient,
        InventoryClient inventoryClient,
        ExecutorService checkoutExecutor
    ) {
        this.fraudClient = fraudClient;
        this.paymentClient = paymentClient;
        this.inventoryClient = inventoryClient;
        this.checkoutExecutor = checkoutExecutor;
    }

    public CheckoutResult checkout(String orderPayload) {
        CompletableFuture<DependencyResult> inventoryFuture =
            CompletableFuture.supplyAsync(
                inventoryClient::checkStock,
                checkoutExecutor
            );

        DependencyResult fraudResult = fraudClient.score(orderPayload);
        if (!(fraudResult instanceof DependencyResult.Success)) {
            return CheckoutResult.rejected(
                "fraud check failed: " + describe(fraudResult)
            );
        }

        DependencyResult paymentResult = paymentClient.charge(orderPayload);
        if (!(paymentResult instanceof DependencyResult.Success)) {
            return CheckoutResult.rejected(
                "payment failed: " + describe(paymentResult)
            );
        }

        DependencyResult inventoryResult = inventoryFuture.join();
        String inventoryStatus = switch (inventoryResult) {
            case DependencyResult.Success s -> s.body();
            case DependencyResult.Failure f -> "pending confirmation";
            case DependencyResult.Timeout t -> "pending confirmation";
        };

        return CheckoutResult.approved(inventoryStatus);
    }

    private String describe(DependencyResult result) {
        return switch (result) {
            case DependencyResult.Success s -> "unexpected success"; // unreachable in practice
            case DependencyResult.Failure f -> f.reason();
            case DependencyResult.Timeout t -> "timed out";
        };
    }
}
