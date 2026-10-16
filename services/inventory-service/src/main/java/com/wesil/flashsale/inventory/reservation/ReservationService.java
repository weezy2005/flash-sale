package com.wesil.flashsale.inventory.reservation;

import java.time.Duration;
import java.util.Optional;

import com.wesil.flashsale.inventory.api.ConflictException;
import com.wesil.flashsale.inventory.product.ProductRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private final ReservationRepository reservations;
    private final ProductRepository products;
    private final Duration ttl;

    public ReservationService(
            ReservationRepository reservations,
            ProductRepository products,
            @Value("${inventory.reservation-ttl:10m}") Duration ttl) {
        this.reservations = reservations;
        this.products = products;
        this.ttl = ttl;
    }

    /** {@code created} is false when this was a replay of an earlier request with the same key. */
    public record ReserveResult(Reservation reservation, boolean created) {
    }

    /**
     * Claims the idempotency key first, then decrements stock. Doing it in this
     * order means duplicate retries queue on the reservation's unique index and
     * never touch the product row, which is the hottest lock in the system
     * during a sale. If stock runs out the whole transaction rolls back,
     * including the reservation row.
     */
    @Transactional
    public ReserveResult reserve(String idempotencyKey, String sku, int quantity) {
        Optional<Reservation> inserted = reservations.insertIfAbsent(idempotencyKey, sku, quantity, ttl);
        if (inserted.isEmpty()) {
            return new ReserveResult(replay(idempotencyKey, sku, quantity), false);
        }
        if (!products.tryDecrement(sku, quantity)) {
            throw new ConflictException("SOLD_OUT", "not enough stock for " + sku);
        }
        return new ReserveResult(inserted.get(), true);
    }

    private Reservation replay(String idempotencyKey, String sku, int quantity) {
        Reservation existing = reservations.findByIdempotencyKey(idempotencyKey).orElseThrow();
        if (!existing.sku().equals(sku) || existing.quantity() != quantity) {
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
                    "idempotency key was already used for a different request");
        }
        return existing;
    }
}
