-- Distinct passengers / legs may have the same service and document kind.
-- Replacement is explicit, never inferred from service/kind alone.
ALTER TABLE travel_documents ADD COLUMN document_group uuid NOT NULL DEFAULT gen_random_uuid();
UPDATE travel_documents d SET document_group=s.id FROM (
 SELECT DISTINCT ON (booking_id,service_key,kind) id,booking_id,service_key,kind
 FROM travel_documents ORDER BY booking_id,service_key,kind,version
) s WHERE d.booking_id=s.booking_id AND d.service_key=s.service_key AND d.kind=s.kind;
ALTER TABLE travel_documents DROP CONSTRAINT travel_documents_booking_id_service_key_kind_version_key;
ALTER TABLE travel_documents ADD CONSTRAINT travel_documents_group_version UNIQUE(booking_id,document_group,version);
DROP INDEX travel_documents_current;
CREATE UNIQUE INDEX travel_documents_current ON travel_documents(document_group) WHERE state='PUBLISHED';
