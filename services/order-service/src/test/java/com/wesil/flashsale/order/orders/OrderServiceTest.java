package com.wesil.flashsale.order.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.wesil.flashsale.order.TestcontainersConfiguration;
import com.wesil.flashsale.order.api.ConflictException;
import com.wesil.flashsale.order.inventory.FakeInventoryClient;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import({TestcontainersConfiguration.class, OrderTestConfiguration.class})
@SpringBootTest
class OrderServiceTest {

    @Autowired
    OrderService service;

    @Autowired
    FakeInventoryClient inventory;

    @Test
    void placingTwiceWithTheSameKeyReturnsTheSameOrder() {
        String key = UUID.randomUUID().toString();
        int callsBefore = inventory.reserveCalls();

        OrderService.PlaceResult first = service.place(key, "sku-a", 1);
        OrderService.PlaceResult replay = service.place(key, "sku-a", 1);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.order().id()).isEqualTo(first.order().id());
        assertThat(first.order().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        // The replay is answered locally without touching inventory again.
        assertThat(inventory.reserveCalls()).isEqualTo(callsBefore + 1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        String key = UUID.randomUUID().toString();
        service.place(key, "sku-a", 1);

        assertConflict(() -> service.place(key, "sku-a", 2), "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void aSoldOutProductIsRejected() {
        String sku = "sku-" + UUID.randomUUID();
        inventory.soldOut(sku);

        assertConflict(() -> service.place(UUID.randomUUID().toString(), sku, 1), "SOLD_OUT");
    }

    @Test
    void payingConfirmsTheReservationAndIsRepeatable() {
        Order order = placeNew();

        assertThat(service.pay(order.id()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(service.pay(order.id()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(inventory.isConfirmed(order.reservationId())).isTrue();
    }

    @Test
    void payingAfterTheReservationExpiredCancelsTheOrder() {
        Order order = placeNew();
        inventory.expire(order.reservationId());

        assertConflict(() -> service.pay(order.id()), "ORDER_EXPIRED");
        assertThat(service.get(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertConflict(() -> service.pay(order.id()), "ORDER_CANCELLED");
    }

    private Order placeNew() {
        return service.place(UUID.randomUUID().toString(), "sku-a", 1).order();
    }

    private static void assertConflict(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
