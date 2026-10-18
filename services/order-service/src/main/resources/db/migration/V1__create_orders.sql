CREATE TABLE orders (
    id              UUID PRIMARY KEY,
    idempotency_key TEXT        NOT NULL UNIQUE,
    sku             TEXT        NOT NULL,
    quantity        INTEGER     NOT NULL CHECK (quantity > 0),
    -- One order per reservation. The reservation lives in inventory-service;
    -- there is deliberately no foreign key across service boundaries.
    reservation_id  UUID        NOT NULL UNIQUE,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CANCELLED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
