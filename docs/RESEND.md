# Resend transactional email

The transactional outboxes use Spring JavaMailSender with Resend SMTP. Account verification, password recovery/change messages and journey/contract/payment/document notifications share this transport. No marketing or SMS is added.

Server-only settings:

- SMTP_HOST=smtp.resend.com
- SMTP_PORT=587
- SMTP_USERNAME=resend
- SMTP_PASSWORD: the Resend API key, used as the SMTP password
- SMTP_FROM=no-reply@aztrip.online
- EMAIL_ENABLED: defaults false; enable after the sender domain/key and delivery test succeed
- EMAIL_DELIVERY_NOT_BEFORE: persisted UTC cutover instant for an existing database; the default epoch retains compatibility for a fresh installation

Spring projects using MAIL_HOST / MAIL_PORT / MAIL_USERNAME / MAIL_PASSWORD / MAIL_FROM map to the corresponding SMTP_* names. Credentials stay only in the ignored backend .env or deployment secret store, never Git, browser variables, logs or EXPO_PUBLIC settings. The sender domain must be verified in the same Resend account and allowed by the configured API key. A sending-restricted key may not read the domain-management API; do not treat that API failure alone as evidence the SMTP password is invalid.

SMTP requires STARTTLS and hostname verification, with bounded timeouts. Existing bounded retry and ambiguous-send handling remain intact. Relay authentication or SMTP acceptance is not proof of inbox delivery.

## Existing outboxes

Activation must not send historical development notifications. Set EMAIL_DELIVERY_NOT_BEFORE once before activating an existing database and retain it across restarts. Account and journey email claim, promotion and worker selection exclude jobs created before this instant. Journey manual retry also respects it. Older jobs remain stored for audit; they are not deleted or marked as sent. This prevents previously queued QA messages from being promoted when email becomes enabled.

## Verification

`./mvnw verify` covers the mail state-machine tests. The opt-in MailCutoverIntegrationTest creates the actual schema in a dedicated loopback PostgreSQL QA database and uses a mocked JavaMailSender. It verifies that old queued/waiting jobs cannot send or promote while new jobs send once. CI runs this check in its own database. Local invocation requires AZERTUR_MAIL_CUTOVER_TEST=1, DATABASE_PASSWORD and MAIL_CUTOVER_JDBC_URL pointing to jdbc:postgresql://127.0.0.1:5432/azertur_qa_mail_cutover_<suffix>.

A single separately authorized external test was attempted with the configured sender; the relay rejected the sender domain as unverified for that key. Automatic delivery remains disabled until the account/domain/key mismatch is resolved. No historical outbox was sent. A later successful test should be recorded separately from inbox-delivery confirmation.

Official settings: https://resend.com/docs/send-with-smtp
