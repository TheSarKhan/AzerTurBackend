# Brevo transactional email

AzərTur uses Brevo's SMTP relay through the existing Spring JavaMailSender. Account verification, password recovery/change notices and office journey/contract/payment/document events keep their existing outbox, ownership and retry semantics. No marketing messages or SMS are added.

Server-only settings:

- SMTP_HOST=smtp-relay.brevo.com
- SMTP_PORT=587
- SMTP_USERNAME: Brevo SMTP login
- SMTP_PASSWORD: Brevo SMTP key, not a Brevo HTTP API key or Gmail app password
- SMTP_FROM: a sender address verified in the Brevo account, independent of the SMTP login
- EMAIL_ENABLED=true only after credentials/sender and the intended outbox have been reviewed

When importing from another Spring project using MAIL_HOST / MAIL_PORT / MAIL_USERNAME / MAIL_PASSWORD, map these to the matching SMTP_* names only if the source credentials actually belong to Brevo. Never infer the provider from the project name. Do not copy unrelated database, JWT, encryption or operations secrets. Keep real values in the ignored backend .env / deployment secret store, never in Git, browser variables or EXPO_PUBLIC settings.

STARTTLS is required and server identity is checked. Connection/read/write timeouts remain 5 seconds. SMTP authentication/parse failures retain the existing bounded retry; ambiguous send results are not automatically resent. Provider SMTP acknowledgement is not proof of inbox delivery.

Enabling email promotes WAITING_CONFIGURATION account and notification jobs. Development/test databases may contain historical synthetic requests; do not enable an unattended worker against that backlog just to test a connection. Validate SMTP authentication without MAIL FROM / RCPT TO / DATA first. A real test message needs a deliberate recipient and should be sent separately from historical queued notifications.

Validated locally: Maven verify and a TLS/certificate handshake to the Brevo relay without authentication or sending email. Credential authentication and actual delivery require provisioned Brevo credentials and a verified sender.

Official documentation: https://developers.brevo.com/docs/smtp-integration
