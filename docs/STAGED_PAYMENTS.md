# Staged payment preparation

Decision: Epoint; the operator sets each deposit amount. Merchant account and test keys are not yet available.

Implemented foundation:
- Operator drafts, previews and sends requests; the customer sees current-contract requests in the travel cabinet. Online payment remains disabled until Epoint is integrated.
- Pending changes and cancellations pause payment requests; no payment is recorded by sending a request.
- Operations issue a deposit against the current accepted PDF contract and sent offer.
- Decimal input is stored as integer minor currency units. Amounts with more than two nonzero decimal places are rejected.
- Non-cancelled requests including the deposit are capped at the accepted offer total.
- Deposit requests are unique per booking; request keys support exact retries and reject altered payloads.
- Service requests require a paid deposit and a service included in the offer.
- Customer reads are ownership-scoped. Pending change/cancellation requests block issuance.
- Legacy demo payment cannot pay a booking with staged payment requests.
- Epoint signature generation and verification follow the documented raw SHA-1 construction.

Not implemented or enabled yet:
- Checkout sessions, payment attempts, callback settlement, reconciliation and retry handling.
- Recording a paid staged request; no endpoint currently marks it paid.
- Cancellation/replacement of issued requests, deposit allocation across services and refunds.
- Payment deadlines and delivered notifications (an optional email outbox event is queued when enabled).

The new request ledger is separate from legacy booking_payments. Do not infer a staged payment from bookings.paid or change requests to PAID manually. The next implementation must validate the current contract and amount again when creating checkout, persist a unique attempt/order before contacting Epoint, and use verified server results to settle exactly once. Browser redirects cannot mark a request paid. Timeouts are unknown results requiring status reconciliation before another charge attempt.

Epoint merchant onboarding must provide verified endpoint URLs, supported settlement currencies, keys, registered callback URL and test scenarios. Public documentation masks endpoint URLs; none have been guessed. Secrets must be server environment variables, never frontend values.

References:
https://developer.epoint.az/az/checkout/request
https://developer.epoint.az/az/callbacks

Validation: Maven tests and local PostgreSQL integration via tests/payment_requests.py. This branch prepares payment requests; it does not accept money.
