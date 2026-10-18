package com.wesil.flashsale.order.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Orders' own view of inventory's event. Kept as a separate copy rather than
 * a shared library so the two services can be deployed independently;
 * {@code type} is a plain string so a new event type cannot break parsing.
 */
public record ReservationEvent(
        UUID eventId,
        String type,
        UUID reservationId,
        String sku,
        int quantity,
        OffsetDateTime occurredAt) {
}
