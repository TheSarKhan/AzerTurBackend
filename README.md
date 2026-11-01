# AzerTurBackend

Java 21 / Spring Boot 4.1 / Maven / PostgreSQL / Flyway / Redis / Spring Security / springdoc Swagger.

Packages: `catalog`, `quote`, `operations`, `config`, `shared`. Flyway owns the database schema; Hibernate validates it.

## Start

```sh
cp .env.example .env
# Fill DATABASE_PASSWORD with a random value. BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD
# (at least 12 characters) create the first administrator while no staff account exists.
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

Office staff sign in with email and password (`POST /api/v1/staff/auth/login`) and receive an opaque bearer token valid for 12 hours; only its SHA-256 hash is stored. Roles: `OPERATOR` (review queue, `/api/v1/operations/**`) and `ADMIN` (everything an operator can do plus staff management under `/api/v1/admin/staff`). Administrators create accounts, change roles, reset passwords and deactivate people; any of those revokes the person's sessions immediately, and the last active administrator can never be removed or demoted. The first administrator comes from `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` on an empty staff table. Public endpoints are explicitly allowed; other endpoints are denied by default. Deploy with TLS; production user identity, customer auth, durable reservations, payment callbacks and operator workflows remain future implementation. Prices and currency conversion are sample values.

See [architecture](docs/ARCHITECTURE.md). The local `.env` is never committed.

## Customer review workflow update

The customer registration, durable reservation request, office approval and demo-payment flows are now implemented. See [the current architecture, API and remaining production work](docs/ARCHITECTURE.md); that document supersedes the initial foundation-only notes above. No cloud deployment or live payment/provider connection is included.
