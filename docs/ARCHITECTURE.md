# Customer reservations and office review

## Agreed scope

Website, customer mobile app and office dashboard. Customer registration is mandatory to submit a reservation. Drivers and guides use separate products. The office checks availability before approval and handles changes and cancellations. EN/RU/AZ and EUR/USD/AZN are supported. Payments remain a demo; 30% deposit, seven-day balance date and 72-hour quote expiry are provisional demo rules. Email uses Gmail SMTP, WhatsApp and push use separate providers; no SMS. Cloud deployment is deferred.

## Implemented flow

1. Browse tours and calculate an itemised, server-priced quote.
2. Register/sign in with email and password. Website tokens stay in HttpOnly cookies; native tokens use Expo SecureStore. The backend stores only SHA-256 token hashes; sessions expire after seven days and can be revoked at logout. Passwords use BCrypt and authentication attempts are rate-limited in Redis.
3. Submit a durable reservation request, initially `PENDING_REVIEW`.
4. Office staff approve availability (`AWAITING_PAYMENT`) or reject with a customer-visible response. No payment is allowed before approval.
5. Successful demo deposit moves the booking to `CONFIRMED`; the balance can then be simulated. Failed payments do not alter funds. Payment keys and row locks protect against retries and concurrent double payment.
6. Customers submit change/cancellation requests. Open requests pause further payment. Office staff respond; accepting a change requires a new, unexpired backend quote and updates the reservation's date, selected services and totals. Paid currency cannot change and a total below the amount paid requires a separate manual refund process. Cancellation is employee-controlled; a paid demo cancellation is visibly marked for manual refund review.
7. Every transition writes an audit event and an email/WhatsApp/push outbox entry. Email delivery is disabled by default. Newly queued emails can use Gmail SMTP once explicitly configured; old `WAITING_CONFIGURATION` rows are not automatically sent. WhatsApp and push remain waiting for provider connection. No external messages were sent during development tests.

The immutable original quote key keeps reservation retries idempotent even after an amendment changes the active quote. Quote payloads are server snapshots; clients never supply authoritative totals. Customers can access only their own reservations and requests. Office endpoints require the separate `OPERATIONS` role.

## API

- `POST /api/v1/auth/register`, `/login`; `GET /me`; `POST /logout`.
- `GET /api/v1/tours`; `POST /api/v1/quotes`.
- `GET|POST /api/v1/bookings`; `GET /bookings/requests`.
- `POST /bookings/{id}/demo-payment` with `Idempotency-Key`.
- `POST /bookings/{id}/requests` (`CHANGE` or `CANCEL`).
- Office: `GET /api/v1/operations/bookings`, `/requests`, `/notifications`.
- Office: `POST /operations/bookings/{id}/review`; `POST /operations/requests/{id}/resolve`.
- Office: `GET /operations/bookings/{id}/audit`.

Swagger `/swagger-ui.html`, schema `/v3/api-docs`. The website BFF restricts routes, checks same-origin writes, redacts access tokens and proxies only customer endpoints. Native and dashboard use configurable direct API addresses. Office sign-in remains the environment-provided HTTP Basic foundation; individual staff identity/admin provisioning is still production work.

## Before production

Email verification, password recovery and individual staff account management; real partner inventory/availability adapters and reconciliation; production payment provider/webhooks and approved refund policy; WhatsApp account and push project/device registration; approved company/legal/contact details; retention/backups/monitoring; signed device builds and store checks. All provider credentials belong in private runtime configuration.

SMS, driver/guides' own apps and cloud deployment are outside this implementation slice. The general marketing contact form remains an explicit non-sending preview. Live partner availability is not fabricated; the office can check it manually before approval. Demo refunds are not silently treated as real refunds.

## Checks

Backend `./mvnw verify`; with PostgreSQL/Redis/API running: `python3 tests/smoke.py` and `python3 tests/booking_flow.py`. The latter covers ownership, role isolation, approval gates, payment failure/idempotency, change/cancellation resolution and concurrent writes.

Website `npm run typecheck`, `node tests/localization.mjs`, `npm run build`; `node tests/customer-bff.mjs` additionally needs the running backend and website. Dashboard `npm run build`. Mobile `npm run typecheck` and Expo Android/iOS bundle exports; exports are not APK/IPA builds or physical-device tests.
