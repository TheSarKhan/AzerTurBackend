# AzerTurBackend

Java 21 / Spring Boot 4.1 / Maven / PostgreSQL / Flyway / Redis / Spring Security / springdoc Swagger.

Packages: `catalog`, `quote`, `operations`, `config`, `shared`. Flyway owns the database schema; Hibernate validates it.

## Start

```sh
cp .env.example .env
# Fill DATABASE_PASSWORD and OPERATIONS_PASSWORD with separate random values.
# OPERATIONS_PASSWORD must be at least 16 characters.
docker compose up -d
```

Or run dependencies in Docker and Java locally:

```sh
docker compose up -d postgres redis
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

API: http://localhost:8080. Swagger: http://localhost:8080/swagger-ui.html. OpenAPI: http://localhost:8080/v3/api-docs.

Docker publishes services only on loopback. Local Java also binds loopback by default; `SERVER_ADDRESS` can explicitly change this for device development. Compose sets it to `0.0.0.0` inside the container.

## Checks

```sh
./mvnw verify
python3 tests/smoke.py
```

Smoke checks require the running local API and load operations credentials from the ignored `.env` file. They create sample quotes in the local database. Set `FRONTEND_ORIGIN=http://localhost:3000` to also verify the Next.js proxy.

The initial operations account uses HTTP Basic and the `OPERATIONS` role. Public endpoints are explicitly allowed; other endpoints are denied by default. Deploy with TLS; production user identity, customer auth, durable reservations, payment callbacks and operator workflows remain future implementation. Prices and currency conversion are sample values.

See [architecture](docs/ARCHITECTURE.md). The local `.env` is never committed.

## Customer review workflow update

The customer registration, durable reservation request, office approval and demo-payment flows are now implemented. See [the current architecture, API and remaining production work](docs/ARCHITECTURE.md); that document supersedes the initial foundation-only notes above. No cloud deployment or live payment/provider connection is included.
