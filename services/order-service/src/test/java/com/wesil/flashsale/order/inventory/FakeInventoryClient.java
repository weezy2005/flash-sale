package com.wesil.flashsale.order.inventory;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.wesil.flashsale.order.api.ConflictException;

/** In-memory inventory that honours the same idempotency contract as the real one. */
public class FakeInventoryClient implements InventoryClient {

    private final Map<String, InventoryReservation> byKey = new ConcurrentHashMap<>();
    private final Set<String> soldOut = ConcurrentHashMap.newKeySet();
    private final Set<UUID> expired = ConcurrentHashMap.newKeySet();
    private final Set<UUID> confirmed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger reserveCalls = new AtomicInteger();

    @Override
    public InventoryReservation reserve(String idempotencyKey, String sku, int quantity) {
        reserveCalls.incrementAndGet();
        if (soldOut.contains(sku)) {
            throw new ConflictException("SOLD_OUT", "not enough stock for " + sku);
        }
        return byKey.computeIfAbsent(idempotencyKey,
                k -> new InventoryReservation(UUID.randomUUID(), sku, quantity, OffsetDateTime.now().plusMinutes(10)));
    }

    @Override
    public void confirm(UUID reservationId) {
        if (expired.contains(reservationId)) {
            throw new ConflictException("RESERVATION_EXPIRED", "reservation has expired");
        }
        confirmed.add(reservationId);
    }

    public void soldOut(String sku) {
        soldOut.add(sku);
    }

    public void expire(UUID reservationId) {
        expired.add(reservationId);
    }

    public boolean isConfirmed(UUID reservationId) {
        return confirmed.contains(reservationId);
    }

    public int reserveCalls() {
        return reserveCalls.get();
    }
}
