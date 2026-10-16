package com.wesil.flashsale.inventory.product;

import com.wesil.flashsale.inventory.api.NotFoundException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    public record CreateProductRequest(@NotBlank String sku, @NotBlank String name, @Min(0) int stock) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Product create(@Valid @RequestBody CreateProductRequest request) {
        products.insert(request.sku(), request.name(), request.stock());
        return new Product(request.sku(), request.name(), request.stock(), request.stock());
    }

    @GetMapping("/{sku}")
    public Product get(@PathVariable String sku) {
        return products.findBySku(sku).orElseThrow(() -> new NotFoundException("product " + sku));
    }
}
