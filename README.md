# Flash Sale

A microservices checkout platform built to survive flash-sale traffic: tens of
thousands of buyers competing for a few hundred units in the same minute.

The core guarantee is **no overselling**, regardless of concurrency, retries, or
pods dying mid-request.

## Services

| Service | Stack | Responsibility |
|---|---|---|
| `inventory-service` | Java 21, Spring Boot, PostgreSQL, Kafka | Stock levels and time-limited reservations |
| `order-service` | Java 21, Spring Boot, PostgreSQL, Kafka | Orders and payment, kept in sync from inventory events |

Each service owns its own database. They talk over HTTP when the buyer needs an
answer now, and over Kafka for everything that happens later.

```
            POST /orders                  POST /reservations
 buyer ───────────────────► order-service ─────────────────────► inventory-service
                                 ▲          (HTTP, idempotent)        │
                                 │                                    │ same transaction
                                 │                                    ▼
                                 │                              outbox_events
                                 │                                    │ relay
                                 └──── inventory.reservation-events ◄─┘
                                              (Kafka)
```

## How a purchase works

1. **Place an order.** The order service forwards the buyer's `Idempotency-Key`
   to inventory, which reserves stock with a single conditional
   `UPDATE ... WHERE available >= :qty`. Two buyers can never both get the last
   unit, and a `CHECK (available >= 0)` constraint backs that up.
2. **Hold.** The reservation holds the stock for 10 minutes.
3. **Pay.** Paying confirms the reservation in inventory and marks the order paid.
4. **Expire.** If nobody pays, a sweeper on every inventory replica releases the
   hold and returns the stock. An `EXPIRED` event then cancels the order.

## Design decisions

- **Idempotency everywhere.** Every write can be retried safely. Reserving
  claims the idempotency key before touching stock, so retries queue on the
  key's unique index instead of the hot product row.
- **Guarded state transitions.** Every status change is
  `UPDATE ... WHERE status = 'RESERVED'` (or `'PENDING_PAYMENT'`). Racing
  confirm/release/expire calls get exactly one winner, and final states can't
  be undone by late or duplicate messages.
- **Transactional outbox.** Events are written in the same transaction as the
  change they describe, then relayed to Kafka. An event is never lost after a
  commit, and never published for a change that rolled back.
- **No leader election.** The sweeper and the outbox relay run on every
  replica and split work with `FOR UPDATE SKIP LOCKED`.
- **Deadlock-free restocking.** A sweep restocks products in SKU order, so two
  replicas always take row locks in the same order.
- **Database clock for expiry.** Expiry uses Postgres's `now()`, so replicas
  with skewed clocks still agree on when a hold lapses.

## Tests

Tests run against real PostgreSQL and Kafka via Testcontainers (Docker
required). Highlights:

- 1,000 concurrent buyers for 100 units: exactly 100 succeed.
- Confirm and release fired at the same instant: exactly one wins.
- Outbox events reach Kafka; rolled-back reservations emit nothing.
- Duplicate and stale events can't change a final order status.
- Starting the order service before inventory still consumes every partition
  (a bug found by running the system end to end).

```sh
cd services/inventory-service && ./mvnw test
cd services/order-service && ./mvnw test
```

## Running locally

```sh
docker compose up -d                     # Postgres x2 and Kafka
cd services/inventory-service && ./mvnw spring-boot:run    # :8080
cd services/order-service && ./mvnw spring-boot:run        # :8081
```

```sh
curl -X POST localhost:8080/api/v1/products -H 'Content-Type: application/json' \
  -d '{"sku":"sneaker","name":"Limited sneaker","stock":100}'

curl -X POST localhost:8081/api/v1/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: buyer-1' -d '{"sku":"sneaker","quantity":1}'

curl -X POST localhost:8081/api/v1/orders/{id}/pay
```

Ports can be changed with `--server.port`, and the order service finds
inventory via `--order.inventory.base-url`.
