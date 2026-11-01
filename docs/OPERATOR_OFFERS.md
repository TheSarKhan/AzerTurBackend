# Operator offers v1 — implementation and verification

Approved by Sarkhan (“Basla”) after all three mockups, 1 November 2026. The exact source PNGs and prompts are preserved beside this record.

## Implemented

Office request queue and full review workspace; independent service editor; offer composer; persisted notes, supplier/check/policy data and customer prices; customer-only preview; explicit send; sent revision persistence; customer account delivery and per-service policy display. UI supports EN/RU/AZ. The existing decline action remains available with a customer-visible reason. Unsaved edits prompt before navigation/sign-out and browser unload.

Draft saves use optimistic versions under a booking row lock. Preview marks exactly the saved version; any save invalidates it. Send checks the current source quote, availability, supplier, cancellation policy, message, return route and pending customer requests. Duplicate sends of one version return the existing revision. Edits after sending remain private until a new immutable revision is sent. Changes to the original quote invalidate old-source offers. A rebuilt draft must be reviewed again.

Customer payloads are explicit public DTOs. Office and supplier internal notes and supplier-only fields are excluded, including from preview. The customer sees the original request estimate separately from the sent offer. Sending never changes booking status to awaiting payment, never charges money and does not create a contract. The legacy approval-to-demo-payment endpoint is blocked for offer-managed requests; rejection remains possible. Legacy unmanaged demo reservations remain compatible.

## Backend

Flyway V5 creates offer drafts/revisions; V6 records their source quote. API paths:
- GET/POST /api/v1/operations/bookings/{id}/offer
- POST /api/v1/operations/bookings/{id}/offer/preview
- POST /api/v1/operations/bookings/{id}/offer/send
- GET /api/v1/operations/bookings/{id}/customer
- GET /api/v1/operations/bookings/{id}
- GET /api/v1/bookings/offers (only the signed-in customer's latest valid-source revisions)

Service amounts use bounded unit quantities and decimal unit prices, rounded per line with exact decimal half-up arithmetic to match current whole-currency quote accounting. Currency stays consistent with the request. The server computes totals. Audit events and the existing outbox record sends; no automatic SMS or real payment was introduced.

## Verification

Passed Maven verify and local Docker build/migrations; API smoke; legacy booking-flow integration; new offer_flow.py; dashboard production build/TypeScript; frontend production build/TypeScript and existing localization, discovery, pricing and cabinet regressions.

Offer integration coverage: validation, role boundaries and ownership, draft privacy, stale saves, concurrent save conflicts, immutable sent revisions, repeated/concurrent send idempotency, preview invalidation, missing service data, pending request gates, amended-source invalidation, fractional AZN unit pricing and rejection after a draft. Internal-note canaries were absent from public DTOs.

Browser walkthrough used a dedicated QA customer and booking ff93685c-a9be-4aae-91dd-5dc8e17d3855. Edited all five services, saved/reopened the draft, previewed €495 without private notes, explicitly sent revision 1, and verified the customer account received the itinerary, five service amounts, message and expandable cancellation terms with no payment action. Checked the reject dialog and Escape behavior without rejecting this demo booking. Desktop and 390px customer mobile showed no horizontal document overflow; temporary viewport overrides were reset. Saved screenshots remain in the workspace archive.

No real supplier booking, contract, charge or external email was sent. The only send walkthrough targeted a clearly marked local QA account. Cloud deployment and main-branch merges remain separate.

## Remaining stages

PDF contract generation/preview/approval and deposit/service payment requests remain planned. This version deliberately provides customer offer review, without a fake acceptance/payment action. Real partner connectors, live rates and Gmail SMTP configuration remain later work. Service/provider/time-specific cancellation rules are manually entered and must be supplied by the company/provider; the QA policy text is not a commercial policy.

Additional price regression: 100 × 0.145 rounds to 15 in both backend decimal arithmetic and dashboard integer-micro arithmetic. Dashboard CI runs the pricing regression.
