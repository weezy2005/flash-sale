package com.wesil.flashsale.order.inventory;

import java.util.Map;
import java.util.UUID;

import com.wesil.flashsale.order.api.ConflictException;
import com.wesil.flashsale.order.api.NotFoundException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HttpInventoryClient implements InventoryClient {

    private final RestClient http;
    private final JsonMapper json;

    public HttpInventoryClient(
            RestClient.Builder builder,
            JsonMapper json,
            @Value("${order.inventory.base-url:http://localhost:8080}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
        this.json = json;
    }

    private record ReserveRequest(String sku, int quantity) {
    }

    @Override
    public InventoryReservation reserve(String idempotencyKey, String sku, int quantity) {
        return http.post()
                .uri("/api/v1/reservations")
                .header("Idempotency-Key", idempotencyKey)
                .body(new ReserveRequest(sku, quantity))
                .retrieve()
                .onStatus(s -> s.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                    throw new NotFoundException("product " + sku);
                })
                .onStatus(s -> s.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                    throw conflict(res);
                })
                .body(InventoryReservation.class);
    }

    @Override
    public void confirm(UUID reservationId) {
        http.post()
                .uri("/api/v1/reservations/{id}/confirm", reservationId)
                .retrieve()
                .onStatus(s -> s.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                    throw conflict(res);
                })
                .toBodilessEntity();
    }

    /** Re-raises inventory's problem detail with its code intact. */
    ConflictException conflict(ClientHttpResponse res) {
        try {
            Map<?, ?> problem = json.readValue(res.getBody(), Map.class);
            return new ConflictException(String.valueOf(problem.get("code")), String.valueOf(problem.get("detail")));
        } catch (Exception e) {
            return new ConflictException("INVENTORY_CONFLICT", "inventory rejected the request");
        }
    }
}
