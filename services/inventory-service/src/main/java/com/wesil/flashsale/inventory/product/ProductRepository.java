package com.wesil.flashsale.inventory.product;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String sku, String name, int stock) {
        jdbc.sql("""
                INSERT INTO products (sku, name, total_stock, available)
                VALUES (:sku, :name, :stock, :stock)
                """)
                .param("sku", sku)
                .param("name", name)
                .param("stock", stock)
                .update();
    }

    public Optional<Product> findBySku(String sku) {
        return jdbc.sql("SELECT sku, name, total_stock, available FROM products WHERE sku = :sku")
                .param("sku", sku)
                .query(Product.class)
                .optional();
    }

    public boolean exists(String sku) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM products WHERE sku = :sku)")
                .param("sku", sku)
                .query(Boolean.class)
                .single();
    }

    /**
     * Decrements stock only if enough remains. The check and the write are a
     * single statement, so Postgres evaluates the WHERE clause against the
     * latest committed row while holding its lock: two concurrent buyers can
     * never both see the last unit. Returns false when stock is insufficient.
     */
    public boolean tryDecrement(String sku, int quantity) {
        int updated = jdbc.sql("""
                UPDATE products
                   SET available = available - :qty, updated_at = now()
                 WHERE sku = :sku AND available >= :qty
                """)
                .param("sku", sku)
                .param("qty", quantity)
                .update();
        return updated == 1;
    }

    public void increment(String sku, int quantity) {
        jdbc.sql("""
                UPDATE products
                   SET available = available + :qty, updated_at = now()
                 WHERE sku = :sku
                """)
                .param("sku", sku)
                .param("qty", quantity)
                .update();
    }
}
