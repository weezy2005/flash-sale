package com.wesil.flashsale.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.wesil.flashsale.inventory.TestcontainersConfiguration;
import com.wesil.flashsale.inventory.api.ConflictException;
import com.wesil.flashsale.inventory.product.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Hammers a single product from many threads at once. A latch releases every
 * thread at the same instant to maximise contention on the product row, which
 * is exactly where a read-then-write implementation would oversell.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReservationConcurrencyTest {

    private static final int STOCK = 100;
    private static final int BUYERS = 1_000;

    @Autowired
    ReservationService service;

    @Autowired
    ProductRepository products;

    @Autowired
    JdbcClient jdbc;

    @Test
    void neverSellsMoreThanStockUnderContention() throws Exception {
        String sku = newProduct();

        List<Outcome> outcomes = runConcurrently(BUYERS, i -> service.reserve("buyer-" + i + "-" + sku, sku, 1));

        assertThat(outcomes).filteredOn(o -> o == Outcome.RESERVED).hasSize(STOCK);
        assertThat(outcomes).filteredOn(o -> o == Outcome.SOLD_OUT).hasSize(BUYERS - STOCK);
        assertThat(products.findBySku(sku).orElseThrow().available()).isZero();
        assertThat(reservationCount(sku)).isEqualTo(STOCK);
    }

    @Test
    void retriesWithTheSameKeyReserveOnlyOnce() throws Exception {
        String sku = newProduct();
        String key = "retrying-client-" + sku;

        List<Outcome> outcomes = runConcurrently(50, i -> service.reserve(key, sku, 2));

        assertThat(outcomes).containsOnly(Outcome.RESERVED);
        assertThat(products.findBySku(sku).orElseThrow().available()).isEqualTo(STOCK - 2);
        assertThat(reservationCount(sku)).isEqualTo(1);
    }

    private enum Outcome { RESERVED, SOLD_OUT }

    private interface Attempt {
        void run(int buyer);
    }

    private List<Outcome> runConcurrently(int threads, Attempt attempt) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                int buyer = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        attempt.run(buyer);
                        return Outcome.RESERVED;
                    } catch (ConflictException e) {
                        assertThat(e.code()).isEqualTo("SOLD_OUT");
                        return Outcome.SOLD_OUT;
                    }
                }));
            }
            start.countDown();
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get());
        }
        return outcomes;
    }

    private String newProduct() {
        String sku = "sku-" + UUID.randomUUID();
        products.insert(sku, "Limited sneaker", STOCK);
        return sku;
    }

    private int reservationCount(String sku) {
        return jdbc.sql("SELECT count(*) FROM reservations WHERE sku = :sku")
                .param("sku", sku)
                .query(Integer.class)
                .single();
    }
}
