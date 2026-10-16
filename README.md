# Flash Sale

A microservices checkout platform built to survive flash-sale traffic: tens of
thousands of buyers competing for a few hundred units in the same minute.

The core guarantee is **no overselling**, regardless of concurrency, retries, or
pods dying mid-request.

## Services

| Service | Stack | Responsibility |
|---|---|---|
| `inventory-service` | Java 21, Spring Boot, PostgreSQL | Stock levels and reservations |

## Running locally

```sh
docker compose up -d postgres
cd services/inventory-service
./mvnw spring-boot:run
```
