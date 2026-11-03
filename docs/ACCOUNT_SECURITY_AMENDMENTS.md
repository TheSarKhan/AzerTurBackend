# Verified customer accounts and paid booking amendments

This branch builds on `feature/complete-office-workflows`. Apply V15/V16 through Flyway; do not recreate existing customer or financial tables. No provider payment/refund, Gmail delivery, push send, cloud deploy or merge was performed during QA.

## Account setup

Set `ACCOUNT_LINK_KEY` to base64 for 32 cryptographically random bytes in server secret storage. Generate a fresh value for each environment; preserve it over restarts. It must never be an `EXPO_PUBLIC_`, `NEXT_PUBLIC_` or `VITE_` variable. The application fails startup for a missing or malformed key. The checked-in CI value is exclusively a disposable integration fixture.

Set `PORTAL_ORIGIN` to the actual customer website HTTPS origin, without a path, query or fragment. Loopback HTTP is allowed for development. The server constructs email links from this configured origin, never a request Host header. Configure the existing Gmail SMTP settings and `EMAIL_ENABLED=true` for actual delivery. Local QA explicitly keeps email and push disabled.

Existing accounts remain unverified; they can sign in, read their owned trips and request verification. Booking creation, contract/plan/amendment acceptance, customer service requests and financial writes require verified email. Notification read state is still available before verification. There is no application-level QA bypass.

Verification links expire in 24 hours; reset links expire in 30 minutes. Tokens contain 32 random bytes, are stored only as SHA-256 hashes and are consumed under a customer lock. Email payloads in the queue use AES-256-GCM, a random nonce and job UUID as associated data. Delivered or revoked jobs have encrypted payloads removed. Forgotten-password requests return the same asynchronous response for known and unknown addresses. Existing Redis attempt limits also apply to recovery and link consumption.

Links use a URL fragment. The web page removes the fragment from browser history immediately and requires an explicit confirmation/password form submission. Password recovery does not auto-login or implicitly verify email. It revokes every issued link, session and push token for the customer and queues a password-change notice without credentials. Login and reset serialize on the customer row so an old password cannot create a session after the reset commits.

Customers can list device descriptions/activity/expiry and revoke their other sessions, or sign out everywhere. Public session IDs are separate from bearer-token hashes. Browser bearer tokens remain in the existing HttpOnly cookie; native storage remains the existing SecureStore implementation.

SMTP acknowledgement uncertainty is recorded as `UNKNOWN` and is not retried automatically. Missing configuration keeps jobs waiting; known authentication/parse failures have bounded retries. Do not rotate the encryption key casually: undelivered encrypted jobs need the existing key. Plan a rotation with queued-job revocation and fresh customer links. Back up and protect the environment secret with the database operational plan.

Security references: [OWASP recovery guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html), [email validation and verification](https://cheatsheetseries.owasp.org/cheatsheets/Email_Validation_and_Verification_Cheat_Sheet.html), [session management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html).

## Amendment workflow

A paid or previously accepted-contract booking cannot be changed by the old direct office-approval route. Create a customer CHANGE request, save its current payment allocation and open the amendment workspace. The source is the current accepted contract plus its active services, received funds, retired funds, cancellation revision and allocation version.

1. Calculate a replacement quote for the new dates, travelers, route and selected services. A booking with any received payment retains its currency, even if its current active funds are zero.
2. Review each old active service's exact contract policy, supplier reference and time bands. The CHANGE request timestamp selects the applicable band. No universal 48/72-hour rule or assumed fee is supplied.
3. Confirm new suppliers, availability, service prices and cancellation terms. Calculate the settlement before PDF preparation, then allocate the carried payment across all new services.
4. Save the optimistic draft version, upload a valid PDF up to 5 MB, open it and confirm review. Final preview and send bind to that draft version and SHA-256. Sending requires FINANCE permission.
5. The customer sees the new services, old policy fees, price, refund, carried funds, balance and original/new PDF. Acceptance binds to proposal ID, version and SHA-256. Internal notes, supplier evidence and calculation basis are excluded.

For active paid funds P, supplier retention C and new service total T: refund R = max(0, P − C − T); carried amount A = P − C − R; remaining balance = T − A. Amounts use integer currency minor units. A must be allocated explicitly by the operator within each service's price. Existing offer unit-price rounding is retained.

Sending leaves the booking and original contract effective. Editing a private draft does not retract an already published proposal. Sending a newer version supersedes the earlier published version. Changed source funds, allocation, contract, cancellation or quote expiry prevent stale acceptance. Repeated sends/acceptances are idempotent.

Acceptance locks the booking and atomically publishes the effective offer, records the exact accepted PDF, supersedes the previous contract, updates quote/date/route/services, creates the settlement and accepted payment plan, records new service allocations and resolves the request. Old OPEN payment requests close; PAID receipts stay in history. Financial fees/refunds are retired from active funds without rewriting or fabricating bank receipts.

Service revisions distinguish an old cancelled hotel from a newly introduced hotel service. Previous cancellation records and refunds stay readable. The refund queue includes both cancellation and amendment refunds, using the same finance permissions, optimistic versions and evidence requirements. Recording a manual transfer is an office report; no bank transfer is executed by this change.

## Verification

Run Java checks with `./mvnw verify`. With the API and named compose test database running on loopback, opt in to synthetic `@example.com` fixtures:

```sh
ALLOW_QA_ACCOUNT_FIXTURES=1 python3 tests/account_security_flow.py
ALLOW_QA_ACCOUNT_FIXTURES=1 python3 tests/amendment_flow.py
ALLOW_QA_ACCOUNT_FIXTURES=1 python3 tests/amendment_revision_flow.py
ALLOW_QA_ACCOUNT_FIXTURES=1 python3 tests/booking_flow.py
ALLOW_QA_ACCOUNT_FIXTURES=1 python3 tests/offer_flow.py
ALLOW_QA_ACCOUNT_FIXTURES=1 ALLOW_LOCAL_FINANCIAL_FIXTURES=1 python3 tests/office_workflows.py
```

The account test decrypts only synthetic queued fixture mail through a standard Java AES-GCM helper, keeping tokens and keys out of output. Other integration tests verify their synthetic accounts directly in the fixture database; production writes still require real verification. Never run these fixtures against a production database.

Tests cover expired/used/wrong-purpose links, concurrent verification, reset and session revocation; encrypted SMTP acknowledgement handling; immutable proposals and ownership; fee/refund arithmetic; concurrent acceptance; historical receipts/PDFs; decline; and cancellation isolation between service revisions. Web/dashboard builds and native TypeScript/lint/Android JavaScript export are additional client checks. An Android export is not a signed APK or device test.
