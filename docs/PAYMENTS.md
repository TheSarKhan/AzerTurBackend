# Payments: recorded by finance, online payment later

There is no demo payment. Money is taken outside the platform until a payment provider (Epoint) is connected, and finance staff record it.

## Flow

1. The office sends an offer and a PDF contract; the customer approves the contract.
2. The office issues a **deposit request**, later **service requests** (`POST /api/v1/operations/bookings/{id}/payment-requests`). Each has an exact amount and appears in the customer's account and the mobile app.
3. The customer pays using the details the team gives them (bank transfer, cash, card terminal).
4. A finance employee (`ROLE_FINANCE`, also granted to admins) opens the booking in the dashboard, chooses **Mark as paid** on the request and records: the amount (typed again to confirm; it must match the request exactly), the method, an optional reference and the date received.
   `POST /api/v1/operations/bookings/{id}/payment-requests/{requestId}/mark-paid`
5. The request becomes `PAID`; the first paid deposit moves the booking from `PENDING_REVIEW` to `CONFIRMED`. An audit entry (`PAYMENT_RECEIVED`) is written, the customer gets an in-app/email/push notification (`PAYMENT_RECEIVED`), and the received amount counts towards service allocations and refunds.

Recording is idempotent by state: a paid request cannot be paid again (`PAYMENT_ALREADY_RECORDED`). The staff-entered reference is visible to staff only.

## Data

`staged_payment_requests` gains `paid_at`, `payment_method`, `payment_reference`, `recorded_by` (migrations `V20__recorded_payments.sql` in `db/migration`, `V9__recorded_payments.sql` in `db/production-migration`). A check constraint requires date, method and recorder whenever a request is `PAID`. Online payment will fill the same columns.

## Not in place yet

Epoint checkout, callbacks, status reconciliation and provider refunds. See `STAGED_PAYMENTS.md`.

## Older data

Rows created by the removed demo payment have `provider='DEMO'` in `booking_payments` and increased `bookings.paid`. They are not real money. Review them before launch (see the deployment notes in the pull request).
