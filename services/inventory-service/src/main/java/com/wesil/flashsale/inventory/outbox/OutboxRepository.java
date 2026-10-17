package com.wesil.flashsale.inventory.outbox;

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
}
