package com.wesil.flashsale.order.events;

import com.wesil.flashsale.order.orders.OrderRepository;
import com.wesil.flashsale.order.orders.OrderStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps orders in step with reservations that change outside the request
 * path: the sweeper expiring an unpaid hold, or a confirm whose order update
 * was lost to a crash.
 *
 * Delivery is at-least-once and two relays may publish out of order, so
 * every action here is a guarded transition out of PENDING_PAYMENT. A
 * duplicate or late event finds the order already final and changes nothing,
 * which is why no processed-events table is needed.
 */
@Component
public class ReservationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventListener.class);

    private final OrderRepository orders;
    private final JsonMapper json;

    public ReservationEventListener(OrderRepository orders, JsonMapper json) {
        this.orders = orders;
        this.json = json;
    }

    @KafkaListener(topics = "${order.inventory.events-topic:inventory.reservation-events}")
    public void on(String payload) {
        ReservationEvent event = json.readValue(payload, ReservationEvent.class);
        OrderStatus target = switch (event.type()) {
            case "CONFIRMED" -> OrderStatus.PAID;
            case "RELEASED", "EXPIRED" -> OrderStatus.CANCELLED;
            default -> null;
        };
        if (target == null) {
            return;
        }
        orders.transitionFromPendingByReservation(event.reservationId(), target)
                .ifPresent(o -> log.info("order {} -> {} from reservation event {}", o.id(), target, event.type()));
    }
}
