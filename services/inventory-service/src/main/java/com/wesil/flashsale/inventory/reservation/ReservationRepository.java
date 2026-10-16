package com.wesil.flashsale.inventory.reservation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.wesil.flashsale.inventory.api.NotFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private static final String COLUMNS = "id, idempotency_key, sku, quantity, status, expires_at, created_at";

    static final RowMapper<Reservation> MAPPER = ReservationRepository::map;

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new reservation unless one already exists for the idempotency
     * key. A concurrent duplicate blocks on the unique index until the first
     * transaction finishes, then gets an empty result instead of an error.
     *
     * Expiry is computed from the database clock, not the JVM's, so pods with
     * skewed clocks still agree on when a reservation lapses.
     */
    public Optional<Reservation> insertIfAbsent(String idempotencyKey, String sku, int quantity, Duration ttl) {
        try {
            return jdbc.sql("""
                    INSERT INTO reservations (id, idempotency_key, sku, quantity, status, expires_at)
                    VALUES (:id, :key, :sku, :qty, 'RESERVED', now() + make_interval(secs => :ttl))
                    ON CONFLICT (idempotency_key) DO NOTHING
                    RETURNING %s
                    """.formatted(COLUMNS))
                    .param("id", UUID.randomUUID())
                    .param("key", idempotencyKey)
                    .param("sku", sku)
                    .param("qty", quantity)
                    .param("ttl", ttl.toSeconds())
                    .query(MAPPER)
                    .optional();
        } catch (DataIntegrityViolationException e) {
            // The idempotency conflict is absorbed by ON CONFLICT, so the only
            // constraint left to violate is the foreign key on sku.
            throw new NotFoundException("product " + sku);
        }
    }

    public Optional<Reservation> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT %s FROM reservations WHERE idempotency_key = :key".formatted(COLUMNS))
                .param("key", idempotencyKey)
                .query(MAPPER)
                .optional();
    }

    public Optional<Reservation> findById(UUID id) {
        return jdbc.sql("SELECT %s FROM reservations WHERE id = :id".formatted(COLUMNS))
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    private static Reservation map(ResultSet rs, int row) throws SQLException {
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("sku"),
                rs.getInt("quantity"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
