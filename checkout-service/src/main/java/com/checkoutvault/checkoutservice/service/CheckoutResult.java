package com.checkoutvault.checkoutservice.service;

/**
 * Result of a checkout attempt, returned by CheckoutController.
 *
 * inventoryStatus is null when the order was rejected before Inventory's
 * result was needed.
 */
public record CheckoutResult(
        Status status,
        String reason,
        String inventoryStatus
) {
    public enum Status {
        APPROVED, REJECTED
    }

    public static CheckoutResult rejected(String reason) {
        return new CheckoutResult(Status.REJECTED, reason, null);
    }

    public static CheckoutResult approved(String inventoryStatus) {
        return new CheckoutResult(Status.APPROVED, null, inventoryStatus);
    }
}