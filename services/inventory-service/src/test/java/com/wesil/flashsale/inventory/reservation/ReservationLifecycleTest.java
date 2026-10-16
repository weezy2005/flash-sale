package com.wesil.flashsale.inventory.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.wesil.flashsale.inventory.TestcontainersConfiguration;
import com.wesil.flashsale.inventory.api.ConflictException;
import com.wesil.flashsale.inventory.product.ProductRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Confirm, release and expiry rules. The scheduled sweeper is pushed out to an
 * hour so it cannot release reservations behind a test's back; tests that need
 * expiry call {@link ReservationService#releaseExpired} directly.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "inventory.sweeper.interval-ms=3600000")
class ReservationLifecycleTest {

    private static final int STOCK = 10;

    @Autowired
    ReservationService service;

    @Autowired
    ProductRepository products;

    @Autowired
    JdbcClient jdbc;

    @Test
    void confirmingTwiceIsANoOp() {
        String sku = newProduct();
        Reservation r = reserve(sku, 2);

        assertThat(service.confirm(r.id()).status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(service.confirm(r.id()).status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(available(sku)).isEqualTo(STOCK - 2);
    }

    @Test
    void releasingTwiceReturnsStockOnce() {
        String sku = newProduct();
        Reservation r = reserve(sku, 3);

        assertThat(service.release(r.id()).status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(service.release(r.id()).status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(available(sku)).isEqualTo(STOCK);
    }

    @Test
    void cannotReleaseAConfirmedReservation() {
        String sku = newProduct();
        Reservation r = reserve(sku, 1);
        service.confirm(r.id());

        assertConflict(() -> service.release(r.id()), "RESERVATION_CONFIRMED");
        assertThat(available(sku)).isEqualTo(STOCK - 1);
    }

    @Test
    void cannotConfirmAReleasedReservation() {
        String sku = newProduct();
        Reservation r = reserve(sku, 1);
        service.release(r.id());

        assertConflict(() -> service.confirm(r.id()), "RESERVATION_RELEASED");
    }

    @Test
    void cannotConfirmAnExpiredReservationEvenBeforeTheSweeperRuns() {
        String sku = newProduct();
        Reservation r = reserve(sku, 1);
        expire(r.id());

        assertConflict(() -> service.confirm(r.id()), "RESERVATION_EXPIRED");
    }

    @Test
    void releasingExpiredReservationsRestocksThem() {
        String sku = newProduct();
        Reservation expired = reserve(sku, 2);
        Reservation live = reserve(sku, 3);
        expire(expired.id());

        drainExpired();

        assertThat(status(expired.id())).isEqualTo(ReservationStatus.RELEASED);
        assertThat(status(live.id())).isEqualTo(ReservationStatus.RESERVED);
        assertThat(available(sku)).isEqualTo(STOCK - 3);
    }

    @Test
    void anExplicitReleaseAfterTheSweeperDoesNotRestockAgain() {
        String sku = newProduct();
        Reservation r = reserve(sku, 4);
        expire(r.id());

        drainExpired();
        service.release(r.id());

        assertThat(available(sku)).isEqualTo(STOCK);
    }

    /**
     * Each reservation gets a confirm and a release fired at the same instant.
     * Exactly one may win, and stock must reflect whichever did.
     */
    @Test
    void confirmRacingReleaseHasExactlyOneWinner() throws Exception {
        String sku = newProduct();
        List<Reservation> reserved = new ArrayList<>();
        for (int i = 0; i < STOCK; i++) {
            reserved.add(reserve(sku, 1));
        }

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Reservation r : reserved) {
                pool.submit(() -> race(start, () -> service.confirm(r.id())));
                pool.submit(() -> race(start, () -> service.release(r.id())));
            }
            start.countDown();
        }

        long confirmed = reserved.stream()
                .filter(r -> status(r.id()) == ReservationStatus.CONFIRMED)
                .count();
        assertThat(reserved).allSatisfy(r -> assertThat(status(r.id())).isNotEqualTo(ReservationStatus.RESERVED));
        assertThat(available(sku)).isEqualTo(STOCK - (int) confirmed);
    }

    private static Void race(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try {
            action.run();
        } catch (ConflictException e) {
            // The loser of the race is expected to be told the other side won.
            assertThat(e.code()).isIn("RESERVATION_CONFIRMED", "RESERVATION_RELEASED");
        }
        return null;
    }

    private static void assertConflict(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private void drainExpired() {
        while (service.releaseExpired(500) > 0) {
            // Other tests share the database, so keep going until nothing is left.
        }
    }

    private String newProduct() {
        String sku = "sku-" + UUID.randomUUID();
        products.insert(sku, "Limited sneaker", STOCK);
        return sku;
    }

    private Reservation reserve(String sku, int quantity) {
        return service.reserve(UUID.randomUUID().toString(), sku, quantity).reservation();
    }

    private void expire(UUID id) {
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", id)
                .update();
    }

    private ReservationStatus status(UUID id) {
        return service.get(id).status();
    }

    private int available(String sku) {
        return products.findBySku(sku).orElseThrow().available();
    }
}
