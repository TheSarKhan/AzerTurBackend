# Nearby events

Customers browse events by city or near their location; office staff manage them. Events are
plain text only and entered by staff for now (an external feed is a later phase).

## Public API (no login, GET only)

- `GET /api/v1/cities` → `[{slug, nameEn, nameRu, nameAz, latitude, longitude}]`
- `GET /api/v1/events` → `{items, page, size, total}`. Parameters: `city`, `category`
  (`CONCERT|FESTIVAL|SPORT|CULTURE|MARKET|FOOD|FAMILY|OTHER`), `from`, `to` (`yyyy-MM-dd`, meaning
  that day in Asia/Baku, or an ISO instant), `q` (≤ 80 characters; matches titles and venue),
  `lat` + `lng` (both or neither), `radiusKm` (1–200, default 25), `page`, `size` (1–50, default 20).
  - Only `PUBLISHED` events that have not ended; default `from` is now.
  - Without `lat`/`lng`: sorted by start time. With them: within `radiusKm`, sorted by distance
    (then start time) and every item carries `distanceKm`.
  - Invalid input returns 400 with `{"error": "INVALID_LOCATION" | "INVALID_RADIUS" | "INVALID_DATE" | ...}`.
- `GET /api/v1/events/{slug}` → the event plus `descriptionEn/Ru/Az`, `address`, `ticketUrl`.

Each item has `titleEn/Ru/Az` (Russian falls back to English), city names, venue, `startsAt`/`endsAt`
(UTC instants; show them in Asia/Baku), `timeKnown`, `free`, `priceFromMinor` (minor units),
`currency`, `image`, `latitude`, `longitude`.

**`timeKnown: false`** means only the date is known: `startsAt` is that day at 00:00 Baku time and
`endsAt` the end of the day. Clients must show the date without a clock time.

Responses to coordinate searches are `Cache-Control: no-store`. Coordinates are used for the query
only; the application stores and logs none. Clients round them to two decimals (about 1 km) before
sending. Note that a reverse proxy in front of the API logs request URLs by default; keep query
strings out of its access log for `/api/v1/events`.

Public reads are limited to 120 requests per minute per visitor address (`CF-Connecting-IP`, then
`X-Real-IP`, then the socket address) and fail open if Redis is down.

## Office API (staff; reads, writes, publish and cancel need `OPERATIONS`, delete needs `ADMIN`)

Browsers may only call GET and POST (CORS), so updates and deletes are POSTs.

- `GET /api/v1/operations/events?status&city&category&q&page&size` (all statuses)
- `GET /api/v1/operations/events/{id}` → `{event, audit}`
- `POST /api/v1/operations/events` → 201, always created as `DRAFT`
- `POST /api/v1/operations/events/{id}` → update; send the current `version`, a stale one is `409 STALE_EVENT`
- `POST /api/v1/operations/events/{id}/publish`, `/cancel` (optional `{note}`), `/delete` (drafts only, ADMIN)

Rules: AZ and EN titles, venue, category, a known city, coordinates in range and `endsAt >= startsAt`
are required; publishing also needs AZ and EN descriptions and an event that has not ended.
`ticketUrl` and `image` must be `https`; text containing markup (`<b>`, `<script>`) is refused.
Every change writes an `event_audit` row (kept after a draft is deleted). `sourceNote` records where
the details were checked.

## Data

Migration `V8_1__nearby_events.sql` exists in **both** `db/migration` and `db/production-migration`
(identical). Version 8.1 sorts after the production bridge's V8 and before the full chain's V9, so
it applies in either folder without renumbering, and it does not depend on any booking table.
Tables: `cities` (seeded: Baku, Quba, Gabala, Sheki), `events`, `event_audit`. Distance is computed
with the haversine formula after a bounding-box prefilter in SQL; no PostGIS.

Tests: `GeoTest`, `EventRulesTest` (JUnit) and `tests/events_flow.py` (public reads, near-me order and
radius edge, validation, roles, audit, rate limit).
