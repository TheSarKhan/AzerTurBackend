# Cancellation assessment API

Supports full-booking and selected-service CANCEL requests, assessed separately per contracted service. Bank refund execution is not implemented.

Operations:
- POST /api/v1/operations/requests/{id}/cancellation-assessment: save reviewed service allocations/rules with optimistic version.
- GET the same path: inspect the saved calculation.
- POST .../approve with the saved version: approve the plan and resolve cancellation atomically. This does not transfer money or mark a refund completed.

The operator supplies each service's supplier reference, the exact policy text from the accepted offer, an explanation of the supplier rule interpretation, received-payment allocation and explicit time bands. No universal deadline, rate or default band exists. Automated interpretation of free-text policies is not implemented. The supplier reference is manually verified by staff because previous offer revisions did not preserve private supplier identities.

The cancellation request creation time selects the latest applicable fromInclusive band (UTC instant). A band applies at its exact boundary. Percentage retention can use PAID_AMOUNT or SERVICE_PRICE; fixed fees are added and withholding is capped at that service's allocated received payment. Minor units use HALF_UP rounding for percentage fees. No additional debt is inferred.

Allocations must cover all contracted services, equal received funds and stay within each service price. Open unpaid requests are not received funds. Save and approval are guarded by booking locks, accepted-contract/source checks and version/paid-amount checks. Approval cancels open payment requests. Original allocation, policy, applied rule, assessment actor and approval time are retained. Repeated approval is idempotent.

The old resolve endpoint cannot bypass assessment for paid or contracted bookings. Paid legacy demo bookings without a verifiable accepted contract require a migration/manual review path; no unverifiable automatic refund is allowed.

The approved UI now supplies operator review and customer breakdowns. Outstanding: external supplier confirmation integrations and Epoint refund execution/reconciliation. Current states describe a reviewed/approved plan only.

## Approved cancellation UI integration

The approved customer and office screens now connect to this API. V10 adds service selections, cancellation records, and separate customer explanation/internal note storage. A partial approval keeps unaffected services active; subsequent requests operate only on remaining services and remaining received-payment allocations. A full remaining-service cancellation closes the booking.

GET/POST /api/v1/bookings/{id}/cancellation provides owned context and request submission.
GET /api/v1/bookings/{id}/cancellation-updates returns approved customer-safe breakdowns (no supplier references, rule interpretation or internal notes).
GET /api/v1/bookings/{id}/cancellation-updates/{request}/pdf returns an owned approved summary with embedded Unicode font.
GET /api/v1/operations/requests/{id}/cancellation-context provides staff context and selection.

Pre-contract zero-payment cancellation is supported for the entire request only. Partial cancellations require an accepted contract. Paid legacy bookings without a valid accepted source require manual migration. After partial cancellation, new payment requests and offer amendments are blocked until an explicit revised plan can be reviewed; this avoids reviving cancelled services or requesting stale prices.

Current refund status is PENDING or NOT_REQUIRED. No endpoint records or claims that bank funds have been returned.

## Office workflow completion

See [OFFICE_WORKFLOWS.md](OFFICE_WORKFLOWS.md) for persistent received-fund allocation, reviewed remaining-service plans, customer acceptance and separate manual refund tracking. Refund status may now be PROCESSING, COMPLETED_MANUAL, FAILED or UNKNOWN in addition to PENDING/NOT_REQUIRED. COMPLETED_MANUAL records the office report, not a provider-verified transfer.
