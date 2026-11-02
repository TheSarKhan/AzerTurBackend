# Contract review v1

Office staff upload a PDF against the latest sent offer, inspect it, then send the saved version. Customers can preview/download the PDF and explicitly confirm the exact version. Sending and confirmation do not trigger payment or finalize a booking.

PDFs are kept as bytes in PostgreSQL (maximum 5 MB, 1–100 pages; encrypted or malformed PDFs are rejected). Every version is bound to the offer revision and SHA-256; acceptance records customer and time. A newer offer hides the prior document from the active customer flow and requires another document. Staff receive a stale-offer warning. Access requires the relevant customer session or operations role. A contract-sent email is queued only when email is configured; otherwise it waits for configuration.

This is a development implementation, not a declaration that the click constitutes a legally sufficient signature. Obtain the company's reviewed contract text and assess jurisdiction-specific acceptance, retention, access, encryption, backups, file security, notification delivery and operational controls before real-customer use. The deposit/payment integration remains separate.
