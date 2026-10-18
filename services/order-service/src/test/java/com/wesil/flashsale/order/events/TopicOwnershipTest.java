package com.wesil.flashsale.order.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.wesil.flashsale.order.TestcontainersConfiguration;
import com.wesil.flashsale.order.inventory.FakeInventoryClient;
import com.wesil.flashsale.order.orders.Order;
import com.wesil.flashsale.order.orders.OrderService;
import com.wesil.flashsale.order.orders.OrderStatus;

import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression test for starting the order service before inventory has ever
 * run. The consumer used to auto-create the topic with one partition, and
 * events inventory later wrote to the other partitions went unconsumed until
 * the next metadata refresh, minutes later.
 */
@Import({TestcontainersConfiguration.class, TopicOwnershipTest.FakeInventory.class})
@SpringBootTest
class TopicOwnershipTest {

    private static final String TOPIC = "inventory.reservation-events";

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeInventory {
        @Bean
        @Primary
        FakeInventoryClient fakeInventory() {
            return new FakeInventoryClient();
        }
    }

    @Autowired
    KafkaAdmin admin;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    OrderService service;

    @Autowired
    JsonMapper json;

    @Test
    void theConsumerNeverCreatesTheTopicAndPicksUpEveryPartitionOnceInventoryDoes() throws Exception {
        // Give the listener time to subscribe and poll against a missing topic.
        Thread.sleep(5_000);
        try (AdminClient client = AdminClient.create(admin.getConfigurationProperties())) {
            assertThat(client.listTopics().names().get()).doesNotContain(TOPIC);
        }

        // What inventory-service does on startup.
        admin.createOrModifyTopics(TopicBuilder.name(TOPIC).partitions(6).replicas(1).build());

        Order order = service.place(UUID.randomUUID().toString(), "sku-a", 1).order();
        ReservationEvent event = new ReservationEvent(
                UUID.randomUUID(), "EXPIRED", order.reservationId(), "sku-a", 1, OffsetDateTime.now());
        // The last partition: the one a single-partition subscription would miss.
        kafka.send(TOPIC, 5, order.reservationId().toString(), json.writeValueAsString(event)).get();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (service.get(order.id()).status() != OrderStatus.CANCELLED && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(service.get(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
    }
}
