# Mobile customer web release

Host: https://app.aztrip.online. Expo exports are served by a private loopback container on port 8104. Existing Nginx terminates TLS and proxies the requested virtual host. The existing API remains on port 8101. The original website and office dashboard retain their hosts and containers.

## Database history

The deployed main branch had V1, V2, V3, V4, V7 (staff accounts). Development had a different V7 (contracts). Do not repair Flyway checksums or run the development migrations against that production history.

Production uses `SPRING_FLYWAY_LOCATIONS=classpath:db/production-migration`: exact old scripts plus V8, containing the previously tested customer workflows V5–V18 in dependency order. The default development path retains its existing history; V19 adds legacy staff tables for the compatible bearer-session endpoints. No customer rows, existing staff users, password hashes, or sessions are replaced. Legacy email-based staff login and the newer operations Basic authentication both remain supported.

Before rollout, the job makes a private PostgreSQL custom-format backup, restores it into a disposable database, starts the new API with email and push disabled, verifies staff sessions, catalogue and customer registration/authentication/journeys, then removes the disposable database and container. Immediately before the real migration it stops only the API and takes a second backup. The migration is additive, so an old-image rollback can keep the new tables and avoid discarding newer customer data.

## Secrets and notifications

The existing GitHub SSH secrets are reused inside Actions. The SMTP credential is an encrypted Actions secret and is transmitted over SSH stdin; never add it to source, command arguments or logs. The deployment files containing resolved server configuration are mode 600 and backups are private. Account-link encryption and operations keys are generated on the host once and preserved on repeat releases.

Resend sends from no-reply@aztrip.online. `EMAIL_DELIVERY_NOT_BEFORE` is persisted at cutover, excluding historic outbox jobs. Email verification and recovery links use the app domain and URL fragments. The static server redirects legacy locale/account paths while preserving fragments; the app consumes the token in memory and removes it from the address bar. Native applications retain their existing deep-link token handling.

## Release and recovery

Dispatch the backend Deploy workflow on the reviewed release branch, `target=mobile-web`, `mobile_action=deploy`, and the exact tested 40-character mobile-client SHA. Both Docker images use source SHA tags. The production `compose.override.yml` pins the selected API and mobile images; it must be retained during future deployments. Inspect `/opt/aztrip/mobile-release.json` for the active source commits and backup directory. Do not print the resolved Compose configuration because it contains secrets.

A failed API startup restores the previous Compose override and previous image. If a proxy/certificate step fails after the API is healthy, retain the upgraded API, inspect the private error log in that release's backup directory, and retry only the proxy step. Existing website and office virtual hosts are backed up before any edits. PostgreSQL restore is a deliberate recovery action; do not restore an old backup over new customer writes merely to roll back code.

Live payment checkout still requires the Epoint merchant configuration. Demo payment mutations are disabled on the public production API. Encrypted offline documents and device push belong to native builds; web customers can explicitly download or share original operator-issued documents.
