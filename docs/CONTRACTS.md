# Contract review v1

Office staff upload a PDF against the latest sent offer, inspect it, then send the saved version. Customers can preview/download the PDF and explicitly confirm the exact version. Sending and confirmation do not trigger payment or finalize a booking.

PDFs are kept as bytes in PostgreSQL (maximum 5 MB, 1–100 pages; encrypted or malformed PDFs are rejected). Every version is bound to the offer revision and SHA-256; acceptance records customer and time. A newer offer hides the prior document from the active customer flow and requires another document. Staff receive a stale-offer warning. Access requires the relevant customer session or operations role. A contract-sent email is queued only when email is configured; otherwise it waits for configuration.

This is a development implementation, not a declaration that the click constitutes a legally sufficient signature. Obtain the company's reviewed contract text and assess jurisdiction-specific acceptance, retention, access, encryption, backups, file security, notification delivery and operational controls before real-customer use. The deposit/payment integration remains separate.

Version consistency update: uploads now require the offerId the operator loaded from GET /operations/bookings/{id}/contract-offer. A changed sent offer is rejected rather than silently attaching the PDF to a different revision. Deploy this API change with the updated dashboard. Re-sending a current accepted document is idempotent; duplicate customer acceptance preserves the original evidence. A replacement PDF, even with identical bytes, receives a new document ID and requires fresh confirmation. Customer consent is reset when the PDF preview closes, and the original acceptance time is displayed.

Additional validation: replacement PDF before/after send, concurrent acceptance, audit evidence count/hash, stale operator offer, and staged-payment regression tests.
