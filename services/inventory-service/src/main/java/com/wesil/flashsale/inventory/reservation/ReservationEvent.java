package com.wesil.flashsale.inventory.reservation;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to {@value ReservationEvents#TOPIC}, keyed by reservation id.
 * {@code eventId} is unique per event so consumers can drop the duplicates
 * that at-least-once delivery will occasionally produce.
 */
public record ReservationEvent(
        UUID eventId,
        Type type,
        UUID reservationId,
        String sku,
        int quantity,
        OffsetDateTime occurredAt) {

    public enum Type {
        RESERVED,
        CONFIRMED,
        /** Released on request, e.g. the buyer cancelled. */
        RELEASED,
        /** Released by the sweeper because it was never confirmed. */
        EXPIRED
    }
}
