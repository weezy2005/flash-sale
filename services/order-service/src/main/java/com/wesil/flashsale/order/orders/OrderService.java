package com.wesil.flashsale.order.orders;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.wesil.flashsale.order.api.ConflictException;
import com.wesil.flashsale.order.api.NotFoundException;
import com.wesil.flashsale.order.inventory.InventoryClient;
import com.wesil.flashsale.order.inventory.InventoryReservation;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final InventoryClient inventory;

    public OrderService(OrderRepository orders, InventoryClient inventory) {
        this.orders = orders;
        this.inventory = inventory;
    }

    /** {@code created} is false when this was a replay of an earlier request with the same key. */
    public record PlaceResult(Order order, boolean created) {
    }

    /**
     * Reserves stock first, then records the order. There is no transaction
     * spanning both services; instead the buyer's idempotency key is passed
     * through to inventory. If this pod dies between the two steps, the
     * client's retry gets the same reservation back and the order is
     * recorded then. If the client never retries, the reservation simply
     * expires and its stock returns to the sale.
     */
    public PlaceResult place(String idempotencyKey, String sku, int quantity) {
        Optional<Order> existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new PlaceResult(checkSameRequest(existing.get(), sku, quantity), false);
        }
        InventoryReservation reservation = inventory.reserve(inventoryKey(idempotencyKey), sku, quantity);
        return orders.insertIfAbsent(idempotencyKey, sku, quantity, reservation.id())
                .map(order -> new PlaceResult(order, true))
                // A concurrent duplicate won the insert; it holds the same reservation.
                .orElseGet(() -> new PlaceResult(orders.findByIdempotencyKey(idempotencyKey).orElseThrow(), false));
    }

    private static final Set<String> RESERVATION_GONE = Set.of("RESERVATION_EXPIRED", "RESERVATION_RELEASED");

    /**
     * Records a successful payment. In production this would be the payment
     * provider's success webhook; there is no real provider in this project.
     * Safe to call repeatedly, since providers retry webhooks.
     */
    public Order pay(UUID id) {
        Order order = get(id);
        switch (order.status()) {
            case PAID:
                return order;
            case CANCELLED:
                throw new ConflictException("ORDER_CANCELLED", "order was cancelled");
            case PENDING_PAYMENT:
                break;
        }
        try {
            inventory.confirm(order.reservationId());
        } catch (ConflictException e) {
            if (!RESERVATION_GONE.contains(e.code())) {
                throw e;
            }
            // Paid too late: the hold lapsed and the stock may already be resold.
            // A real system would refund here.
            orders.transitionFromPending(id, OrderStatus.CANCELLED);
            throw new ConflictException("ORDER_EXPIRED", "reservation expired before payment");
        }
        // Empty if the reservation-events consumer already marked it paid.
        return orders.transitionFromPending(id, OrderStatus.PAID).orElseGet(() -> get(id));
    }

    public Order get(UUID id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("order " + id));
    }

    /** Namespaced so a buyer's key can never collide with another inventory client's. */
    static String inventoryKey(String idempotencyKey) {
        return "order:" + idempotencyKey;
    }

    private static Order checkSameRequest(Order order, String sku, int quantity) {
        if (!order.sku().equals(sku) || order.quantity() != quantity) {
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
                    "idempotency key was already used for a different request");
        }
        return order;
    }
}
