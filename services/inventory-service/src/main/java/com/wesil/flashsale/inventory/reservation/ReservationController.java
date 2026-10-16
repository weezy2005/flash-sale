package com.wesil.flashsale.inventory.reservation;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    public record ReserveRequest(@NotBlank String sku, @Min(1) int quantity) {
    }

    @PostMapping
    public ResponseEntity<Reservation> reserve(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReserveRequest request) {
        ReservationService.ReserveResult result =
                service.reserve(idempotencyKey, request.sku(), request.quantity());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.reservation());
    }

    @PostMapping("/{id}/confirm")
    public Reservation confirm(@PathVariable UUID id) {
        return service.confirm(id);
    }

    @PostMapping("/{id}/release")
    public Reservation release(@PathVariable UUID id) {
        return service.release(id);
    }

    @GetMapping("/{id}")
    public Reservation get(@PathVariable UUID id) {
        return service.get(id);
    }
}
