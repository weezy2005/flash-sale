package com.wesil.flashsale.inventory.reservation;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.wesil.flashsale.inventory.outbox.OutboxRepository;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Records reservation events in the outbox as part of the current transaction. */
@Component
public class ReservationEvents {

    public static final String TOPIC = "inventory.reservation-events";

    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock = Clock.systemUTC();

    public ReservationEvents(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void record(ReservationEvent.Type type, Reservation r) {
        ReservationEvent event = new ReservationEvent(
                UUID.randomUUID(), type, r.id(), r.sku(), r.quantity(), OffsetDateTime.now(clock));
        // Keying by reservation id puts every event for one reservation on the
        // same partition. That is not a full ordering guarantee: relays on two
        // pods can publish adjacent batches in either order, so consumers must
        // treat the terminal events (CONFIRMED, RELEASED, EXPIRED) as final
        // rather than relying on arrival order.
        outbox.append(TOPIC, r.id().toString(), json.writeValueAsString(event));
    }
}
