package com.wesil.flashsale.order.inventory;

import java.util.UUID;

/**
 * Inventory's reservation API. Business rejections (sold out, expired, ...)
 * surface as {@link com.wesil.flashsale.order.api.ConflictException} with
 * inventory's error code, so callers can handle them by code.
 */
public interface InventoryClient {

    /** Idempotent: the same key always returns the same reservation. */
    InventoryReservation reserve(String idempotencyKey, String sku, int quantity);

    /** Idempotent: confirming an already confirmed reservation succeeds. */
    void confirm(UUID reservationId);
}
