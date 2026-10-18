package com.wesil.flashsale.order.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.wesil.flashsale.order.TestcontainersConfiguration;
import com.wesil.flashsale.order.events.ReservationEvent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Publishes the events inventory would, straight onto the real topic. */
@Import({TestcontainersConfiguration.class, OrderTestConfiguration.class})
@SpringBootTest
class ReservationEventReconciliationTest {

    private static final String TOPIC = "inventory.reservation-events";

    @Autowired
    OrderService service;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Test
    void anExpiredReservationCancelsItsUnpaidOrder() throws Exception {
        Order order = placeNew();

        publish("EXPIRED", order);
        publish("EXPIRED", order); // at-least-once delivery: duplicates must be harmless

        awaitStatus(order, OrderStatus.CANCELLED);
    }

    @Test
    void aConfirmedReservationMarksAPendingOrderPaid() throws Exception {
        Order order = placeNew();

        publish("CONFIRMED", order);

        awaitStatus(order, OrderStatus.PAID);
    }

    @Test
    void aStaleEventCannotUndoAFinalStatus() throws Exception {
        Order paid = placeNew();
        service.pay(paid.id());
        Order marker = placeNew();

        String partitionKey = paid.reservationId().toString();
        publish("RELEASED", paid, partitionKey);
        // Same key, so same partition: once the marker is consumed, the stale
        // event before it has been consumed too.
        publish("EXPIRED", marker, partitionKey);
        awaitStatus(marker, OrderStatus.CANCELLED);

        assertThat(service.get(paid.id()).status()).isEqualTo(OrderStatus.PAID);
    }

    private Order placeNew() {
        return service.place(UUID.randomUUID().toString(), "sku-a", 1).order();
    }

    private void publish(String type, Order order) throws Exception {
        publish(type, order, order.reservationId().toString());
    }

    private void publish(String type, Order order, String key) throws Exception {
        ReservationEvent event = new ReservationEvent(
                UUID.randomUUID(), type, order.reservationId(), order.sku(), order.quantity(), OffsetDateTime.now());
        kafka.send(TOPIC, key, json.writeValueAsString(event)).get();
    }

    private void awaitStatus(Order order, OrderStatus expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (service.get(order.id()).status() != expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(service.get(order.id()).status()).isEqualTo(expected);
    }
}
