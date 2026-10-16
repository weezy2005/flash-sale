CREATE TABLE products (
    sku         TEXT PRIMARY KEY,
    name        TEXT        NOT NULL,
    total_stock INTEGER     NOT NULL CHECK (total_stock >= 0),
    -- Last line of defence against overselling: even a buggy code path
    -- cannot commit a negative stock level.
    available   INTEGER     NOT NULL CHECK (available >= 0 AND available <= total_stock),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    idempotency_key TEXT        NOT NULL UNIQUE,
    sku             TEXT        NOT NULL REFERENCES products (sku),
    quantity        INTEGER     NOT NULL CHECK (quantity > 0),
    status          TEXT        NOT NULL CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The expiry sweeper only ever looks for live reservations past their deadline,
-- so a partial index keeps it small no matter how many confirmed orders pile up.
CREATE INDEX reservations_expiring_idx ON reservations (expires_at) WHERE status = 'RESERVED';
