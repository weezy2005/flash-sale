package com.wesil.flashsale.inventory.reservation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Reservation(
        UUID id,
        String idempotencyKey,
        String sku,
        int quantity,
        ReservationStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) {
}
