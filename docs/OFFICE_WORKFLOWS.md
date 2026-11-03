# Office workflows — implementation and setup

This completes the requested application workflows for office accounts/roles (3), allocation and refund tracking (1), notifications (4) and hotels/partners (5). The UI continues the approved request dashboard and payment/cancellation design. External credentials, real provider settlement and supplier onboarding are separate deployment inputs.

## Staff and permissions

The environment operations account is the bootstrap administrator. Its credential stays in server configuration. Administrators create named database accounts with ADMIN, OPERATOR or FINANCE role; passwords are BCrypt-hashed, at least 16 characters and at most 72 UTF-8 bytes, and never returned by APIs. Updates require the current version. Username is immutable. The bootstrap name is reserved, and administrators cannot disable or demote themselves. Disabled accounts are rejected at their next request. Basic authentication requires HTTPS outside loopback development.

ADMIN includes operations and finance permissions. FINANCE includes operations and can save allocations, publish revised financial plans and update refund tracking. OPERATOR handles office requests, suppliers and offers but cannot grant accounts, rotate supplier sync keys or mutate finance records. Financial cancellation assessments remain subject to the existing staff review/approval workflow; recording a transfer is finance-only. Administrative changes are recorded in office_audit. No privileged action is authorized by hiding a UI button alone.

GET /operations/me reports effective roles. GET/POST /operations/staff manages accounts. GET /operations/staff/directory returns enabled employees. Administrators can assign a booking to any enabled employee; other employees can take unassigned work or release their own. Expected-owner checks and a booking lock protect concurrent changes.

## Received funds and partial cancellation

GET/POST /operations/bookings/{id}/allocation reads/saves the distribution of actually received funds across all active contracted services. Amounts are integer minor units, must match the current received total after previous cancellations and cannot exceed a service price. Version, contract and paid-amount checks reject stale writes. No endpoint creates a receipt or marks a staged payment paid. Open payment requests are never treated as receipts.

A saved allocation is the source for later cancellation assessment; redistribution during review is rejected. Approval rechecks funds, contract, source quote and saved allocation against the reviewed snapshot. The original accepted contract and approved cancellation decision remain retained.

After a partial cancellation, reallocate remaining funds and POST /operations/bookings/{id}/revised-plan with the current allocationVersion. The plan contains only remaining services, revised total, received share and balance. All old OPEN requests are cancelled on publication. The immutable plan is sent for customer acceptance via GET/POST /bookings/{id}/revised-plan. The customer accepts an exact plan id/version. New payment requests remain blocked until acceptance and are capped both by the remaining journey total and the selected service's allocated/unrequested balance. A new cancellation, allocation or incompatible source invalidates the plan. A revised financial plan is not a replacement PDF contract: a contractual amendment must still use the existing versioned PDF workflow.

The customer payment view shows active services and funds attributed to them. The offer review retains the original estimate and the full received amount. Paid offers/contracts cannot be edited through the pre-payment composition path.

## Refund tracking

GET /operations/refunds lists approved refunds with positive amounts. POST /operations/refunds/{requestId} records progress with version checking. PENDING/FAILED -> PROCESSING or UNKNOWN; PROCESSING/UNKNOWN -> COMPLETED_MANUAL, FAILED or UNKNOWN. COMPLETED_MANUAL is terminal and requires an explicit manual-transfer-verification flag plus a transfer reference. Evidence references and staff notes are not exposed to the customer; only state, time and customer message are shown. This records the office's report, not an independently verified bank settlement. No transfer is performed by this endpoint. The cancellation PDF remains a decision summary rather than a payment receipt.

## Customer notifications and SMTP

A database outbox trigger creates one customer notification per customer/booking/event/transaction even when several delivery channels are queued. Notification text is customer-safe and does not expose the internal outbox body. GET /bookings/notifications returns the latest 200 items plus the full unread count. POST /bookings/notifications/{id}/read and /read-all are ownership-scoped, idempotent, and return 204. Website cabinet and mobile account display these notifications.

The office notification monitor reports actual delivery states and supports manual retry only for known failures or unconfigured email. SMTP setup uses EMAIL_ENABLED, SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD and SMTP_FROM; Gmail requires the account's supported SMTP credential (normally an app password with the required account settings). No SMTP secret belongs in a browser or EXPO_PUBLIC variable. Enabling SMTP promotes historical WAITING_CONFIGURATION email jobs into the delivery queue; review the development backlog before enabling a real sender.

A committed claim precedes SMTP. Concurrent workers do not claim the same job. A successful SMTP acknowledgement is SENT. Authentication/message failures have bounded delayed retries. Transport ambiguity or interrupted delivery becomes DELIVERY_UNKNOWN and is not automatically re-sent; an operator must reconcile externally. SMTP acknowledgement does not establish inbox placement or reading. Tests use a mocked sender; live Gmail delivery was not exercised or enabled.

## Mobile push

The Expo adapter is opt-in with PUSH_ENABLED and optional EXPO_ACCESS_TOKEN. Configure EXPO_PUBLIC_EAS_PROJECT_ID (public UUID) and EAS/APNs/FCM credentials on the mobile project, then build a push-capable native app. The mobile account has in-app notifications and an explicit push-permission/register/disable control. Device tokens are registered through the authenticated /bookings/notifications/push-token API and tied to a live customer session. Queued deliveries are cancelled after session expiry/logout/disable or ownership change.

The worker sends only to the official Expo Push API and stores ticket ids. ACCEPTED means Expo accepted a ticket; PROVIDER_CONFIRMED means a later receipt reports acceptance by the underlying provider, not device display/read. DeviceNotRegistered disables that token. HTTP 429 and an explicit MessageRateExceeded rejection have bounded delayed retries. Known FAILED pushes can be manually retried only for a currently enabled device/session; unknown sends cannot. Ambiguous sends are UNKNOWN and are not automatically repeated. Read-receipt checks can retry without issuing a second push. No real device push was sent in QA.

Official references: https://docs.expo.dev/push-notifications/sending-notifications/ and https://docs.expo.dev/versions/v58.0.0/sdk/notifications/ . SMS is not implemented or sent. The pre-existing WhatsApp queue remains awaiting a separately chosen provider.

## Hotels and partner integrations

The office can create, edit or deactivate partners and their services (hotel, transport, guide, transfer, activity). Services store location, currency, exact unit price, date interval, capacity and cancellation terms. Optimistic versions reject stale edits. Date-only values are serialized without timezone shifts. In the offer service editor, choose an eligible catalogue service to copy supplier, name, price, location and terms. Availability deliberately remains NOT_CHECKED until the office confirms the full itinerary/stay and capacity. Catalogue capacity is a supplier declaration, not an automatic hotel reservation or stock hold.

Administrators generate/rotate a 32-byte partner-specific key. Only its SHA-256 hash is stored. Rotating invalidates the previous key. Share the displayed key securely with that supplier; it is never included in ordinary listings. Partner-side GET/POST /api/v1/partner-sync/{partnerId} requires X-Partner-Key and is scoped to that active partner only. GET returns their service ids/versions; POST upserts a service with id, partnerId, serviceKey, name, location, currency, priceMinor, availableFrom, availableTo, capacity, policy, active and version. Existing ids cannot be moved across partners. Treat 409 as a request to reload current versions. Serve over HTTPS. A concrete hotel vendor must implement this contract or have a vendor-specific adapter added from its verified API documentation; no arbitrary external endpoint was guessed.

## Verification and release boundaries

Maven tests cover existing money/cancellation rules and SMTP acknowledgement/unknown-result behavior. Local integration tests/office_workflows.py exercises roles, disabled accounts, delegated assignment, versions, partner sync/key isolation, notification ownership, cent-accurate allocation and service caps, partial cancellation, exact-version revised-plan acceptance, refund evidence/transitions and privacy. It requires ALLOW_LOCAL_FINANCIAL_FIXTURES=1, a loopback API and the named development PostgreSQL container; it simulates a paid QA receipt only in that database and never calls a payment gateway. CI uses its isolated PostgreSQL service. QA staff accounts are disabled at the end. All test customers/amounts are synthetic.

Website and dashboard production builds pass. Mobile typecheck, ESLint and Android JavaScript bundle export pass under Node 22. The export is not an APK/IPA or a physical-device test. Browser verification covers partner save, account roles, allocation/plan display, notification read state, and the customer plan/refund views. Screenshots are in the workspace design/modules/office-workflows/v1 archive.

Local Docker dependencies were used; the application was run from the Java 21 jar because Docker Hub base-image metadata retrieval stalled. The new API Docker image and live provider delivery were not validated. No cloud deployment or PR merge was performed.
