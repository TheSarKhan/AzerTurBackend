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
