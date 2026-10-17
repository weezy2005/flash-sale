-- Transactional outbox. Events are written in the same transaction as the
-- state change they describe, so an event exists if and only if the change
-- committed. A relay publishes them to Kafka afterwards.
CREATE TABLE outbox_events (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic        TEXT        NOT NULL,
    message_key  TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- The relay only scans the unpublished tail, which stays tiny even when the
-- table holds millions of published rows.
CREATE INDEX outbox_unpublished_idx ON outbox_events (id) WHERE published_at IS NULL;
