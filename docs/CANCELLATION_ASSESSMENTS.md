# Cancellation assessment API

This phase supports full-booking CANCEL requests, assessed separately per contracted service. Partial service cancellation and refund transfer execution are not implemented.

Operations:
- POST /api/v1/operations/requests/{id}/cancellation-assessment: save reviewed service allocations/rules with optimistic version.
- GET the same path: inspect the saved calculation.
- POST .../approve with the saved version: approve the plan and resolve cancellation atomically. This does not transfer money or mark a refund completed.

The operator supplies each service's supplier reference, the exact policy text from the accepted offer, an explanation of the supplier rule interpretation, received-payment allocation and explicit time bands. No universal deadline, rate or default band exists. Automated interpretation of free-text policies is not implemented. The supplier reference is manually verified by staff because previous offer revisions did not preserve private supplier identities.

The cancellation request creation time selects the latest applicable fromInclusive band (UTC instant). A band applies at its exact boundary. Percentage retention can use PAID_AMOUNT or SERVICE_PRICE; fixed fees are added and withholding is capped at that service's allocated received payment. Minor units use HALF_UP rounding for percentage fees. No additional debt is inferred.

Allocations must cover all contracted services, equal received funds and stay within each service price. Open unpaid requests are not received funds. Save and approval are guarded by booking locks, accepted-contract/source checks and version/paid-amount checks. Approval cancels open payment requests. Original allocation, policy, applied rule, assessment actor and approval time are retained. Repeated approval is idempotent.

The old resolve endpoint cannot bypass assessment for paid or contracted bookings. Paid legacy demo bookings without a verifiable accepted contract require a migration/manual review path; no unverifiable automatic refund is allowed.

Outstanding: customer-facing breakdown, operator UI, partial cancellations, supplier confirmation evidence and Epoint refund execution/reconciliation. Current states describe a reviewed/approved plan only.
