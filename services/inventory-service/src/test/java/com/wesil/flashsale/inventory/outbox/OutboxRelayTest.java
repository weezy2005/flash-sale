package com.wesil.flashsale.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.wesil.flashsale.inventory.TestcontainersConfiguration;
import com.wesil.flashsale.inventory.api.ConflictException;
import com.wesil.flashsale.inventory.product.ProductRepository;
import com.wesil.flashsale.inventory.reservation.Reservation;
import com.wesil.flashsale.inventory.reservation.ReservationEvent;
import com.wesil.flashsale.inventory.reservation.ReservationEvents;
import com.wesil.flashsale.inventory.reservation.ReservationService;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxRelayTest {

    @Autowired
    ReservationService service;

    @Autowired
    ProductRepository products;

    @Autowired
    ConsumerFactory<?, ?> consumers;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Test
    void reservationEventsArePublishedToKafka() {
        String sku = newProduct(5);
        Reservation r = service.reserve(UUID.randomUUID().toString(), sku, 2).reservation();
        service.confirm(r.id());

        List<ReservationEvent> events = awaitEvents(r.id(), 2);

        assertThat(events).extracting(ReservationEvent::type)
                .containsExactlyInAnyOrder(ReservationEvent.Type.RESERVED, ReservationEvent.Type.CONFIRMED);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.sku()).isEqualTo(sku);
            assertThat(e.quantity()).isEqualTo(2);
        });
        assertThat(unpublishedFor(sku)).isZero();
    }

    @Test
    void aRolledBackReservationLeavesNoEvent() {
        String sku = newProduct(1);

        assertThatThrownBy(() -> service.reserve(UUID.randomUUID().toString(), sku, 2))
                .isInstanceOf(ConflictException.class);

        int events = jdbc.sql("SELECT count(*) FROM outbox_events WHERE payload->>'sku' = :sku")
                .param("sku", sku)
                .query(Integer.class)
                .single();
        assertThat(events).isZero();
    }

    private List<ReservationEvent> awaitEvents(UUID reservationId, int expected) {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ReservationEvent> found = new ArrayList<>();
        try (Consumer<?, ?> consumer = consumers.createConsumer("test-" + UUID.randomUUID(), null, null, overrides)) {
            consumer.subscribe(List.of(ReservationEvents.TOPIC));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (found.size() < expected && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<?, ?> record : consumer.poll(Duration.ofMillis(500))) {
                    ReservationEvent event = json.readValue((String) record.value(), ReservationEvent.class);
                    if (event.reservationId().equals(reservationId)) {
                        assertThat(record.key()).isEqualTo(reservationId.toString());
                        found.add(event);
                    }
                }
            }
        }
        return found;
    }

    private int unpublishedFor(String sku) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE payload->>'sku' = :sku AND published_at IS NULL")
                .param("sku", sku)
                .query(Integer.class)
                .single();
    }

    private String newProduct(int stock) {
        String sku = "sku-" + UUID.randomUUID();
        products.insert(sku, "Limited sneaker", stock);
        return sku;
    }
}
