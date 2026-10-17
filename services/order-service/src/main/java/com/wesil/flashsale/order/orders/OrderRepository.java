package com.wesil.flashsale.order.orders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private static final String COLUMNS =
            "id, idempotency_key, sku, quantity, reservation_id, status, created_at, updated_at";

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty when an order with this idempotency key already exists. */
    public Optional<Order> insertIfAbsent(String idempotencyKey, String sku, int quantity, UUID reservationId) {
        return jdbc.sql("""
                INSERT INTO orders (id, idempotency_key, sku, quantity, reservation_id, status)
                VALUES (:id, :key, :sku, :qty, :reservationId, 'PENDING_PAYMENT')
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING %s
                """.formatted(COLUMNS))
                .param("id", UUID.randomUUID())
                .param("key", idempotencyKey)
                .param("sku", sku)
                .param("qty", quantity)
                .param("reservationId", reservationId)
                .query(OrderRepository::map)
                .optional();
    }

    public Optional<Order> findById(UUID id) {
        return jdbc.sql("SELECT %s FROM orders WHERE id = :id".formatted(COLUMNS))
                .param("id", id)
                .query(OrderRepository::map)
                .optional();
    }

    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT %s FROM orders WHERE idempotency_key = :key".formatted(COLUMNS))
                .param("key", idempotencyKey)
                .query(OrderRepository::map)
                .optional();
    }

    private static Order map(ResultSet rs, int row) throws SQLException {
        return new Order(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("sku"),
                rs.getInt("quantity"),
                rs.getObject("reservation_id", UUID.class),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
