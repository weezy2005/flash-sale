package com.wesil.flashsale.order.orders;

import java.util.Optional;
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
