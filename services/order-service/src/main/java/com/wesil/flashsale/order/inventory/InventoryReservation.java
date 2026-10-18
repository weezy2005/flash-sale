package com.wesil.flashsale.order.inventory;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The subset of inventory's reservation that orders care about. */
public record InventoryReservation(UUID id, String sku, int quantity, OffsetDateTime expiresAt) {
}
