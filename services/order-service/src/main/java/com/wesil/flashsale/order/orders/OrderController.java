package com.wesil.flashsale.order.orders;

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
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    public record PlaceOrderRequest(@NotBlank String sku, @Min(1) int quantity) {
    }

    @PostMapping
    public ResponseEntity<Order> place(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        OrderService.PlaceResult result = service.place(idempotencyKey, request.sku(), request.quantity());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.order());
    }

    @PostMapping("/{id}/pay")
    public Order pay(@PathVariable UUID id) {
        return service.pay(id);
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable UUID id) {
        return service.get(id);
    }
}
