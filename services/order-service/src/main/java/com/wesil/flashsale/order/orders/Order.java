package com.wesil.flashsale.order.orders;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Order(
        UUID id,
        String idempotencyKey,
        String sku,
        int quantity,
        UUID reservationId,
        OrderStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
