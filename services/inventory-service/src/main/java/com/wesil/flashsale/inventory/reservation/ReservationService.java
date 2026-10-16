package com.wesil.flashsale.inventory.reservation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.wesil.flashsale.inventory.api.ConflictException;
import com.wesil.flashsale.inventory.api.NotFoundException;
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

    /** Confirming twice is a no-op so a retried payment callback is harmless. */
    @Transactional
    public Reservation confirm(UUID id) {
        return reservations.confirmIfLive(id).orElseGet(() -> {
            Reservation current = get(id);
            return switch (current.status()) {
                case CONFIRMED -> current;
                case RELEASED -> throw new ConflictException("RESERVATION_RELEASED", "reservation was released");
                case RESERVED -> throw new ConflictException("RESERVATION_EXPIRED", "reservation has expired");
            };
        });
    }

    /** Releasing twice is a no-op; the stock is only returned on the first transition. */
    @Transactional
    public Reservation release(UUID id) {
        Optional<Reservation> released = reservations.releaseIfReserved(id);
        if (released.isPresent()) {
            products.increment(released.get().sku(), released.get().quantity());
            return released.get();
        }
        Reservation current = get(id);
        if (current.status() == ReservationStatus.CONFIRMED) {
            throw new ConflictException("RESERVATION_CONFIRMED", "reservation is already confirmed");
        }
        return current;
    }

    /**
     * Returns the stock held by one batch of expired reservations. Products are
     * restocked in sku order so two pods sweeping overlapping products always
     * take row locks in the same order and cannot deadlock each other.
     */
    @Transactional
    public int releaseExpired(int batchSize) {
        List<Reservation> expired = reservations.releaseExpired(batchSize);
        Map<String, Integer> restock = new TreeMap<>();
        for (Reservation r : expired) {
            restock.merge(r.sku(), r.quantity(), Integer::sum);
        }
        restock.forEach(products::increment);
        return expired.size();
    }

    public Reservation get(UUID id) {
        return reservations.findById(id).orElseThrow(() -> new NotFoundException("reservation " + id));
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
