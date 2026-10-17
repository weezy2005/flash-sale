package com.wesil.flashsale.inventory.outbox;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Must join the caller's transaction: an event appended outside the
     * transaction that made the change could survive that change rolling back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, String payload) {
        jdbc.sql("""
                INSERT INTO outbox_events (topic, message_key, payload)
                VALUES (:topic, :key, CAST(:payload AS jsonb))
                """)
                .param("topic", topic)
                .param("key", key)
                .param("payload", payload)
                .update();
    }

    /** Locks the oldest unpublished rows, skipping any another relay already holds. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OutboxEvent> claimUnpublished(int limit) {
        return jdbc.sql("""
                SELECT id, topic, message_key, payload::text AS payload
                  FROM outbox_events
                 WHERE published_at IS NULL
                 ORDER BY id
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query((rs, row) -> new OutboxEvent(
                        rs.getLong("id"), rs.getString("topic"), rs.getString("message_key"), rs.getString("payload")))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markPublished(List<Long> ids) {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }
}
