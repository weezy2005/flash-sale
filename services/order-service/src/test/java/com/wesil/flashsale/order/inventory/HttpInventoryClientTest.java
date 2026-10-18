package com.wesil.flashsale.order.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.UUID;

import com.wesil.flashsale.order.api.ConflictException;
import com.wesil.flashsale.order.api.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Checks the wire contract with inventory without starting it. */
class HttpInventoryClientTest {

    private MockRestServiceServer server;
    private HttpInventoryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpInventoryClient(builder, JsonMapper.builder().build(), "http://inventory");
    }

    @Test
    void reserveForwardsTheIdempotencyKey() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://inventory/api/v1/reservations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "order:k1"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"id":"%s","sku":"sku-a","quantity":2,"status":"RESERVED",
                         "expiresAt":"2026-10-17T10:00:00Z"}
                        """.formatted(id)));

        InventoryReservation r = client.reserve("order:k1", "sku-a", 2);

        assertThat(r.id()).isEqualTo(id);
        assertThat(r.quantity()).isEqualTo(2);
        server.verify();
    }

    @Test
    void inventoryConflictCodesSurviveTheHop() {
        server.expect(requestTo("http://inventory/api/v1/reservations"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                        {"status":409,"detail":"not enough stock for sku-a","code":"SOLD_OUT"}
                        """));

        assertThatThrownBy(() -> client.reserve("k", "sku-a", 1))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code()).isEqualTo("SOLD_OUT"));
    }

    @Test
    void anUnknownProductIsNotFound() {
        server.expect(requestTo("http://inventory/api/v1/reservations"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.reserve("k", "nope", 1)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void confirmingAnExpiredReservationIsAConflict() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://inventory/api/v1/reservations/" + id + "/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                        {"status":409,"detail":"reservation has expired","code":"RESERVATION_EXPIRED"}
                        """));

        assertThatThrownBy(() -> client.confirm(id))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("RESERVATION_EXPIRED"));
    }
}
