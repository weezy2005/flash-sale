package com.wesil.flashsale.inventory.product;

public record Product(String sku, String name, int totalStock, int available) {
}
