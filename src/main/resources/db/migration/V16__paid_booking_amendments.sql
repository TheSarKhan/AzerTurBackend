ALTER TABLE bookings ADD COLUMN service_revision integer NOT NULL DEFAULT 1;
ALTER TABLE bookings DROP CONSTRAINT bookings_check1;
ALTER TABLE bookings ADD CONSTRAINT bookings_paid_nonnegative CHECK(paid>=0);
ALTER TABLE cancelled_services ADD COLUMN service_revision integer NOT NULL DEFAULT 1;
ALTER TABLE cancelled_services DROP CONSTRAINT cancelled_services_pkey;
ALTER TABLE cancelled_services ADD PRIMARY KEY(booking_id,service_revision,service_key);
CREATE TABLE amendment_drafts (
 request_id uuid PRIMARY KEY REFERENCES booking_requests(id),version bigint NOT NULL,payload text NOT NULL,
 base_signature char(64) NOT NULL,pdf_content bytea,pdf_filename varchar(160),pdf_sha256 char(64),preview_version bigint NOT NULL DEFAULT 0,
 updated_by varchar(254) NOT NULL,updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE amendment_versions (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES booking_requests(id),booking_id uuid NOT NULL REFERENCES bookings(id),
 draft_version bigint NOT NULL,base_contract_id uuid NOT NULL REFERENCES contract_documents(id),base_signature char(64) NOT NULL,
 target_quote_id uuid NOT NULL REFERENCES quotes(id),public_payload text NOT NULL,private_payload text NOT NULL,
 pdf_content bytea NOT NULL,pdf_filename varchar(160) NOT NULL,pdf_sha256 char(64) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('SENT','ACCEPTED','DECLINED','SUPERSEDED','WITHDRAWN')),
 sent_by varchar(254) NOT NULL,sent_at timestamptz NOT NULL DEFAULT now(),accepted_by uuid REFERENCES customers(id),accepted_at timestamptz,
 applied_contract_id uuid REFERENCES contract_documents(id),UNIQUE(request_id,draft_version)
);
CREATE TABLE amendment_settlements (
 request_id uuid PRIMARY KEY REFERENCES booking_requests(id),booking_id uuid NOT NULL REFERENCES bookings(id),
 amendment_id uuid NOT NULL UNIQUE REFERENCES amendment_versions(id),retired_minor bigint NOT NULL CHECK(retired_minor>=0),
 retained_minor bigint NOT NULL CHECK(retained_minor>=0),refund_minor bigint NOT NULL CHECK(refund_minor>=0),payload text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE refund_tracking DROP CONSTRAINT refund_tracking_request_id_fkey;
ALTER TABLE refund_tracking ADD CONSTRAINT refund_tracking_request_id_fkey FOREIGN KEY(request_id) REFERENCES booking_requests(id);
CREATE INDEX amendment_versions_booking_idx ON amendment_versions(booking_id,sent_at DESC);
