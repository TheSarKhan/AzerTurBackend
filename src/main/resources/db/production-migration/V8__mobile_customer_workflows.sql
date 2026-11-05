-- Source: V5__operator_offer_revisions.sql
CREATE TABLE offer_drafts (
 booking_id uuid PRIMARY KEY REFERENCES bookings(id), version bigint NOT NULL CHECK(version>0),
 payload text NOT NULL, preview_version bigint NOT NULL DEFAULT 0,
 updated_by varchar(254) NOT NULL, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE offer_revisions (
 id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id), revision integer NOT NULL CHECK(revision>0),
 draft_version bigint NOT NULL, payload text NOT NULL, sent_by varchar(254) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(booking_id,revision), UNIQUE(booking_id,draft_version)
);
CREATE INDEX offer_revisions_booking_idx ON offer_revisions(booking_id,revision DESC);


-- Source: V6__offer_source_quote.sql
ALTER TABLE offer_revisions ADD COLUMN source_quote_id uuid REFERENCES quotes(id);


-- Source: V7__contracts.sql
CREATE TABLE contract_documents (
 id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id), offer_id uuid NOT NULL REFERENCES offer_revisions(id),
 version integer NOT NULL, filename varchar(160) NOT NULL, content bytea NOT NULL, sha256 char(64) NOT NULL,
 state varchar(12) NOT NULL CHECK (state IN ('DRAFT','SENT','ACCEPTED','SUPERSEDED')),
 created_by varchar(254) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz,
 accepted_by uuid REFERENCES customers(id), accepted_at timestamptz, UNIQUE(booking_id,version)
);
CREATE INDEX contract_documents_booking_idx ON contract_documents(booking_id,version DESC);


-- Source: V8__staged_payment_requests.sql
CREATE TABLE staged_payment_requests (
 id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id),
 contract_id uuid NOT NULL REFERENCES contract_documents(id),
 kind varchar(12) NOT NULL CHECK(kind IN ('DEPOSIT','SERVICE')),
 service_key varchar(30), amount_minor bigint NOT NULL CHECK(amount_minor>0),
 currency varchar(3) NOT NULL, status varchar(12) NOT NULL DEFAULT 'OPEN'
 CHECK(status IN ('OPEN','PAID','CANCELLED')),
 request_key uuid NOT NULL UNIQUE, created_by varchar(254) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_staged_deposit ON staged_payment_requests(booking_id)
 WHERE kind='DEPOSIT' AND status<>'CANCELLED';
CREATE INDEX staged_payment_requests_booking ON staged_payment_requests(booking_id);


-- Source: V9__cancellation_assessments.sql
CREATE TABLE cancellation_assessments (
 request_id uuid PRIMARY KEY REFERENCES booking_requests(id),
 version bigint NOT NULL CHECK(version>0), contract_id uuid NOT NULL REFERENCES contract_documents(id),
 source_quote_id uuid NOT NULL REFERENCES quotes(id), paid_minor bigint NOT NULL CHECK(paid_minor>=0),
 payload text NOT NULL, state varchar(12) NOT NULL CHECK(state IN ('REVIEWED','APPROVED')),
 reviewed_by varchar(254) NOT NULL, reviewed_at timestamptz NOT NULL DEFAULT now(),
 approved_at timestamptz
);


-- Source: V10__service_cancellations.sql
CREATE TABLE cancellation_selections (
 request_id uuid PRIMARY KEY REFERENCES booking_requests(id),
 service_keys text NOT NULL, reason varchar(200) NOT NULL
);
CREATE TABLE cancelled_services (
 booking_id uuid NOT NULL REFERENCES bookings(id), service_key varchar(30) NOT NULL,
 request_id uuid NOT NULL REFERENCES booking_requests(id),
 allocated_minor bigint NOT NULL CHECK(allocated_minor>=0),
 PRIMARY KEY(booking_id,service_key)
);
ALTER TABLE cancellation_assessments ADD COLUMN customer_explanation varchar(2000) NOT NULL DEFAULT '';
ALTER TABLE cancellation_assessments ADD COLUMN internal_note varchar(4000) NOT NULL DEFAULT '';


-- Source: V11__payment_request_drafts.sql
ALTER TABLE staged_payment_requests ADD COLUMN customer_message varchar(2000) NOT NULL DEFAULT '';
CREATE TABLE payment_request_drafts (
 booking_id uuid PRIMARY KEY REFERENCES bookings(id),
 version bigint NOT NULL CHECK(version>0), preview_version bigint NOT NULL DEFAULT 0,
 payload text NOT NULL, updated_by varchar(254) NOT NULL, updated_at timestamptz NOT NULL DEFAULT now()
);


-- Source: V12__operations_assignment.sql
ALTER TABLE bookings ADD COLUMN assigned_to varchar(254);


-- Source: V13__office_workflows.sql
CREATE TABLE staff_accounts(username varchar(100) PRIMARY KEY,name varchar(100) NOT NULL,password_hash text NOT NULL,role varchar(16) NOT NULL CHECK(role IN ('ADMIN','OPERATOR','FINANCE')),enabled boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE office_audit(id uuid PRIMARY KEY,actor varchar(254) NOT NULL,action varchar(60) NOT NULL,reference varchar(100) NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE partners(id uuid PRIMARY KEY,name varchar(160) NOT NULL,kind varchar(20) NOT NULL CHECK(kind IN ('HOTEL','TRANSPORT','GUIDE','ACTIVITY','OTHER')),email varchar(254) NOT NULL DEFAULT '',phone varchar(60) NOT NULL DEFAULT '',notes varchar(4000) NOT NULL DEFAULT '',active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 1,sync_key_hash varchar(64),created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE partner_services(id uuid PRIMARY KEY,partner_id uuid NOT NULL REFERENCES partners(id),name varchar(160) NOT NULL,service_key varchar(30) NOT NULL CHECK(service_key IN ('hotel','transport','guide','transfer','activity')),location varchar(160) NOT NULL,currency varchar(3) NOT NULL CHECK(currency IN ('EUR','USD','AZN')),price_minor bigint NOT NULL CHECK(price_minor>=0),available_from date NOT NULL,available_to date NOT NULL CHECK(available_to>=available_from),capacity integer NOT NULL CHECK(capacity>=0),policy varchar(4000) NOT NULL,active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 1,updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE payment_allocations(booking_id uuid PRIMARY KEY REFERENCES bookings(id),contract_id uuid NOT NULL REFERENCES contract_documents(id),version bigint NOT NULL,paid_minor bigint NOT NULL,payload text NOT NULL,cancellation_count integer NOT NULL,updated_by varchar(254) NOT NULL,updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE revised_payment_plans(id uuid PRIMARY KEY,booking_id uuid NOT NULL REFERENCES bookings(id),contract_id uuid NOT NULL REFERENCES contract_documents(id),version bigint NOT NULL,payload text NOT NULL,cancellation_count integer NOT NULL,state varchar(20) NOT NULL CHECK(state IN ('SENT','ACCEPTED','SUPERSEDED')),created_by varchar(254) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),accepted_at timestamptz,accepted_by uuid REFERENCES customers(id),UNIQUE(booking_id,version));
CREATE TABLE refund_tracking(request_id uuid PRIMARY KEY REFERENCES cancellation_assessments(request_id),version bigint NOT NULL,state varchar(24) NOT NULL CHECK(state IN ('PENDING','PROCESSING','COMPLETED_MANUAL','FAILED','UNKNOWN')),external_reference varchar(200) NOT NULL DEFAULT '',customer_message varchar(2000) NOT NULL DEFAULT '',updated_by varchar(254) NOT NULL,updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE customer_notifications(id uuid PRIMARY KEY,customer_id uuid NOT NULL REFERENCES customers(id),booking_id uuid REFERENCES bookings(id),event varchar(40) NOT NULL,event_group text NOT NULL UNIQUE,read_at timestamptz,created_at timestamptz NOT NULL DEFAULT now());
CREATE OR REPLACE FUNCTION create_customer_notification() RETURNS trigger AS $$
BEGIN
 INSERT INTO customer_notifications(id,customer_id,booking_id,event,event_group) VALUES(NEW.id,NEW.customer_id,NEW.booking_id,NEW.event,NEW.customer_id::text||':'||coalesce(NEW.booking_id::text,'')||':'||NEW.event||':'||txid_current()::text) ON CONFLICT(event_group) DO NOTHING;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER customer_notification_event AFTER INSERT ON notification_outbox FOR EACH ROW EXECUTE FUNCTION create_customer_notification();
ALTER TABLE notification_outbox ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE notification_outbox ADD COLUMN last_error varchar(60) NOT NULL DEFAULT '';
CREATE INDEX customer_notifications_owner_idx ON customer_notifications(customer_id,created_at DESC);


-- Source: V14__push_delivery.sql
CREATE TABLE customer_push_tokens(token text PRIMARY KEY,customer_id uuid NOT NULL REFERENCES customers(id),enabled boolean NOT NULL DEFAULT true,session_hash varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE push_deliveries(id uuid PRIMARY KEY,outbox_id uuid NOT NULL REFERENCES notification_outbox(id),token text NOT NULL REFERENCES customer_push_tokens(token),state varchar(24) NOT NULL DEFAULT 'QUEUED',attempts integer NOT NULL DEFAULT 0,ticket_id varchar(100),next_attempt_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(outbox_id,token));


-- Source: V15__verified_customer_accounts.sql
ALTER TABLE customers ADD COLUMN email_verified_at timestamptz;
ALTER TABLE customer_sessions ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE customer_sessions ADD CONSTRAINT customer_sessions_id_unique UNIQUE(id);
ALTER TABLE customer_sessions ADD COLUMN device varchar(100) NOT NULL DEFAULT 'Other device';
ALTER TABLE customer_sessions ADD COLUMN last_seen_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE account_links (
 id uuid PRIMARY KEY,customer_id uuid NOT NULL REFERENCES customers(id),purpose varchar(8) NOT NULL CHECK(purpose IN ('VERIFY','RESET')),
 token_hash char(64) NOT NULL UNIQUE,expires_at timestamptz NOT NULL,consumed_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX account_links_customer_idx ON account_links(customer_id,purpose,created_at DESC);
CREATE TABLE account_mail_jobs (
 id uuid PRIMARY KEY,customer_id uuid REFERENCES customers(id),link_id uuid REFERENCES account_links(id),
 purpose varchar(20) NOT NULL CHECK(purpose IN ('VERIFY','RESET_REQUEST','RESET','PASSWORD_CHANGED')),
 cipher bytea,nonce bytea,state varchar(24) NOT NULL CHECK(state IN ('QUEUED','WAITING_CONFIGURATION','SENDING','SENT','UNKNOWN','FAILED','REVOKED','RESOLVED')),
 attempts integer NOT NULL DEFAULT 0,next_attempt_at timestamptz NOT NULL DEFAULT now(),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE customer_security_audit(id uuid PRIMARY KEY,customer_id uuid NOT NULL REFERENCES customers(id),action varchar(40) NOT NULL,created_at timestamptz NOT NULL DEFAULT now());


-- Source: V16__paid_booking_amendments.sql
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


-- Source: V17__original_travel_documents.sql
CREATE TABLE travel_documents (
 id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id),
 service_revision integer NOT NULL, service_key varchar(30) NOT NULL, kind varchar(30) NOT NULL,
 version integer NOT NULL CHECK(version>0), title varchar(160) NOT NULL,
 filename varchar(160) NOT NULL, mime varchar(40) NOT NULL CHECK(mime IN ('application/pdf','image/jpeg','image/png')),
 content bytea NOT NULL CHECK(octet_length(content)>0 AND octet_length(content)<=10000000),
 sha256 char(64) NOT NULL, starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 timezone varchar(80) NOT NULL, instructions varchar(2000) NOT NULL DEFAULT '',
 state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED','SUPERSEDED','REVOKED')),
 created_by varchar(254) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 previewed_by varchar(254), previewed_at timestamptz, published_by varchar(254), published_at timestamptz,
 revoked_at timestamptz, CHECK(ends_at>starts_at), UNIQUE(booking_id,service_key,kind,version)
);
CREATE UNIQUE INDEX travel_documents_current ON travel_documents(booking_id,service_key,kind) WHERE state='PUBLISHED';
CREATE INDEX travel_documents_booking ON travel_documents(booking_id,starts_at);


-- Source: V18__independent_document_groups.sql
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
