# Customer reservations and office review

## Agreed scope

Website, customer mobile app and office dashboard. Customer registration is mandatory to submit a reservation. Drivers and guides use separate products. The office checks availability before approval and handles changes and cancellations. EN/RU/AZ and EUR/USD/AZN are supported. Payments are recorded by finance staff when funds are received (bank transfer, cash, card terminal); online payment through Epoint is not connected yet. Quotes expire after 72 hours. Email uses Gmail SMTP, WhatsApp and push use separate providers; no SMS. Cloud deployment is deferred.

## Implemented flow

1. Browse tours and calculate an itemised, server-priced quote.
2. Register/sign in with email and password. Website tokens stay in HttpOnly cookies; native tokens use Expo SecureStore. The backend stores only SHA-256 token hashes; sessions expire after seven days and can be revoked at logout. Passwords use BCrypt and authentication attempts are rate-limited in Redis.
3. Submit a durable reservation request, initially `PENDING_REVIEW`.
4. Office staff prepare an offer and a contract after checking availability, or reject the request with a customer-visible response. A request cannot be approved without the offer workflow, and no payment is requested before the customer approves the contract.
5. After the customer approves the contract the office issues a deposit request, then service payment requests as services become due. When money is received, finance staff mark the request as paid (typed amount, method, reference, date). The first paid deposit moves the booking to `CONFIRMED`, an audit entry is written and the customer is notified. A request can be marked paid only once; row locks protect against concurrent recording.
6. Customers submit change/cancellation requests. Open requests pause further payment. Office staff respond; accepting a change requires a new, unexpired backend quote and updates the reservation's date, selected services and totals. Paid currency cannot change and a total below the amount paid requires a separate manual refund process. Cancellation is employee-controlled; a paid cancellation is visibly marked for manual refund review.
7. Every transition writes an audit event and an email/WhatsApp/push outbox entry. Email delivery is disabled by default. Newly queued emails can use Gmail SMTP once explicitly configured; old `WAITING_CONFIGURATION` rows are not automatically sent. WhatsApp and push remain waiting for provider connection. No external messages were sent during development tests.

The immutable original quote key keeps reservation retries idempotent even after an amendment changes the active quote. Quote payloads are server snapshots; clients never supply authoritative totals. Customers can access only their own reservations and requests. Office endpoints require the separate `OPERATIONS` role.

## API

- `POST /api/v1/auth/register`, `/login`; `GET /me`; `POST /logout`.
- `GET /api/v1/tours`; `POST /api/v1/quotes`.
- `GET|POST /api/v1/bookings`; `GET /bookings/requests`.
- Office (FINANCE): `POST /operations/bookings/{id}/payment-requests/{requestId}/mark-paid` records received money.
- Public: `POST /api/v1/contact` (contact form, rate limited); office: `GET /operations/contact-messages`, `POST /operations/contact-messages/{id}/handled`.
- `POST /bookings/{id}/requests` (`CHANGE` or `CANCEL`).
- Office: `GET /api/v1/operations/bookings`, `/requests`, `/notifications`.
- Office: `POST /operations/bookings/{id}/review`; `POST /operations/requests/{id}/resolve`.
- Office: `GET /operations/bookings/{id}/audit`.

Swagger `/swagger-ui.html`, schema `/v3/api-docs`. The website BFF restricts routes, checks same-origin writes, redacts access tokens and proxies only customer endpoints. Native and dashboard use configurable direct API addresses. Office sign-in remains the environment-provided HTTP Basic foundation; individual staff identity/admin provisioning is still production work.

## Before production

Email verification, password recovery and individual staff account management; real partner inventory/availability adapters and reconciliation; production payment provider/webhooks and approved refund policy; WhatsApp account and push project/device registration; approved company/legal/contact details; retention/backups/monitoring; signed device builds and store checks. All provider credentials belong in private runtime configuration.

SMS, driver/guides' own apps and cloud deployment are outside this implementation slice. Website contact messages are stored and, when `CONTACT_INBOX` and email are configured, copied to the team inbox. Live partner availability is not fabricated; the office can check it manually before approval. Refunds are tracked manually and are never treated as automatic provider refunds.

## Checks

Backend `./mvnw verify`; with PostgreSQL/Redis/API running: `python3 tests/smoke.py` and `python3 tests/booking_flow.py`. The latter covers ownership, role isolation, approval gates, payment failure/idempotency, change/cancellation resolution and concurrent writes.

Website `npm run typecheck`, `node tests/localization.mjs`, `npm run build`; `node tests/customer-bff.mjs` additionally needs the running backend and website. Dashboard `npm run build`. Mobile `npm run typecheck` and Expo Android/iOS bundle exports; exports are not APK/IPA builds or physical-device tests.


## Operator offer workflow v1

See [OPERATOR_OFFERS.md](OPERATOR_OFFERS.md) for the persisted draft/preview/send workflow, private/public field separation, immutable revisions, source validation, payment gates and verification. This supersedes earlier notes that offer preparation is not implemented. Contracts and staged payments remain planned.

## Office workflows update

[OFFICE_WORKFLOWS.md](OFFICE_WORKFLOWS.md) supersedes the earlier planned-only notes about accounts, staged allocations, partner management and notifications. Provider payment settlement remains disabled.

## Google sign-in

`GET /api/v1/auth/google/config` returns the public OAuth client IDs (`enabled:false` and no button while `GOOGLE_WEB_CLIENT_ID`, `GOOGLE_IOS_CLIENT_ID` and `GOOGLE_ANDROID_CLIENT_ID` are all empty). `POST /api/v1/auth/google` takes `{credential, locale?}` where `credential` is a Google ID token. The API verifies its signature against Google's public keys (`GOOGLE_JWK_SET_URI`), the issuer, an audience equal to one of the configured client IDs, expiry and `email_verified`; it never trusts a client-supplied email.

A known Google identity (`customer_identities`) signs in to its account. An unknown identity with an email that matches an existing account is linked to it; otherwise an account is created. Google has verified the address, so the account counts as email-verified. If the matching account was still unverified, its password, sessions and pending email links are revoked first, so someone who pre-registered another person's address cannot keep access. Google-created accounts get a random unusable password and can set one through the normal reset flow. Sign-in attempts are limited per address only.
