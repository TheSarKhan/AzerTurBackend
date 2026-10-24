# AzerTur repositories

- [Customer mobile app](https://github.com/TheSarKhan/AzerTurMobileClient) — React Native / Expo / TypeScript
- [Backend](https://github.com/TheSarKhan/AzerTurBackend) — Java 21 / Spring Boot / PostgreSQL / Flyway / Redis / Spring Security / Swagger
- [Website](https://github.com/TheSarKhan/AzerTurFrontend) — Next.js App Router / React / TypeScript
- [Operations dashboard](https://github.com/TheSarKhan/AzerTurDashboardFrontend) — React / Vite / TypeScript

## Ownership

The backend owns business validation, price calculations, database migrations, quote persistence, cache and operations access. The website's `/api/quotes` is a transport proxy to Java and contains no price calculator. Mobile and dashboard call Java directly through configurable API URLs. OpenAPI is served by the backend at `/v3/api-docs`; Swagger UI is `/swagger-ui.html`.

The existing website design and its EN/RU content have been retained. Its destination copy, itineraries and display-only starting-price references are sample presentation content; live prices are calculated by Java. All rates and service prices are still demo values. Customer checkout and payment screens remain browser demos, and are not a production booking or authentication service.

## Current API

- `GET /actuator/health` — public readiness summary.
- `GET /api/v1/tours` — database catalogue; Redis cached for five minutes.
- `POST /api/v1/quotes` — validates and calculates a sample quote, persists its summary in PostgreSQL.
- `GET /api/v1/operations/overview` — Spring Security protected; catalogue count, quote count and recent quote summaries.

Operations authentication currently uses an environment-provided account and HTTP Basic. Credentials stay in dashboard memory and are cleared on sign-out; they are not put into Vite environment variables or local storage. Use HTTPS outside local development. Production staff identity/roles, customer login, durable booking/payment workflows, provider integrations, audit logging and catalogue management remain next implementation work.

Quote service choices, currencies, deposit rules, route continuity, expiry and date limits have been moved from the former TypeScript API into Java. The backend stores quote summaries, not customer personal information. No live payment provider is connected.

## Local addresses

Backend: `http://localhost:8080`; website: `http://localhost:3000`; dashboard: `http://localhost:5173`; PostgreSQL: `localhost:5432`; Redis: `localhost:6379`. Native mobile does not need browser CORS. Android Emulator uses `10.0.2.2` for the development computer; a real device requires its LAN address and an explicitly reachable backend.

Each repository is independent, has its own lock/build files, environment example and CI. No secret files, build artifacts, dependency folders or Sites hosting identity belong in GitHub.
