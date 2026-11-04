# Original travel documents

Flyway V17–V18 adds immutable original bytes (PDF/JPG/PNG, <=10,000,000 bytes), validated MIME, SHA-256, per-booking/service/kind version slots, validity instants/timezone, upload/preview/publish audit fields and explicit revoke/archive states. Multipart transport is raised to 10 MB/11 MB while contract uploads retain their own existing 5 MB validation. No airline QR, barcode or boarding pass is synthesized.

`/api/v1/operations/bookings/{id}/documents` lists/uploads; `/{document}/file` retrieves original bytes; POST `preview` records exact-hash operator review; `publish` requires that operator's review plus explicit customer/journey check and latest draft; `revoke` removes validity. Publishing and uploads lock the booking to serialize version creation. Publishing supersedes the prior slot and emits the normal in-app/email/push outbox event. No email is sent when EMAIL_ENABLED=false.

`/api/v1/bookings/{id}/documents`, `/{document}`, `/{document}/file` are customer-owner scoped, exclude all unpublished drafts and use no-store. Published archive files remain retrievable with clearly non-actionable metadata. Service cancellation, booking cancellation, expiry and changes to the booking service revision affect returned status. A supplier document is never represented as freshly synchronized with an airline. Online `verifiedAt` describes this server's record check only.

Storage currently uses PostgreSQL bytea with a hard per-file limit, preserving originals exactly. Plan storage capacity/backup and any object-storage migration before high-volume production. File format parsing/size/dimension checks are not a malware scanning service. Internal QR scanning and airline API acquisition are deferred per user decision.

Validation: `./mvnw verify`; `ALLOW_QA_ACCOUNT_FIXTURES=1 python3 tests/travel_documents.py` against a loopback integration database only. CI includes the latter. Local isolated DB may be selected with `QA_DATABASE_NAME=azertur_documents_qa_<suffix>`. Tests use synthetic example.com accounts and non-travel fixture files; never point fixture helpers at a production database.

Independent passengers/flight legs use separate document groups. Upload defaults to a new document; explicit `replaces` references an existing document in the same booking/service/kind and creates a new version only within that group. Publishing one passenger’s ticket never supersedes a different passenger’s original.
