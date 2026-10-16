package com.wesil.flashsale.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.wesil.flashsale.inventory.TestcontainersConfiguration;
import com.wesil.flashsale.inventory.product.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Proves the sweeper is actually scheduled, not just callable. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "inventory.sweeper.interval-ms=200")
class ExpiredReservationSweeperTest {

    @Autowired
    ReservationService service;

    @Autowired
    ProductRepository products;

    @Autowired
    JdbcClient jdbc;

    @Test
    void releasesExpiredReservationsOnItsOwn() throws InterruptedException {
        String sku = "sku-" + UUID.randomUUID();
        products.insert(sku, "Limited sneaker", 5);
        Reservation r = service.reserve(UUID.randomUUID().toString(), sku, 5).reservation();

        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", r.id())
                .update();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (service.get(r.id()).status() == ReservationStatus.RESERVED && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }

        assertThat(service.get(r.id()).status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(products.findBySku(sku).orElseThrow().available()).isEqualTo(5);
    }
}
