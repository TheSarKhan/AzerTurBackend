CREATE TABLE contract_documents (
 id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id), offer_id uuid NOT NULL REFERENCES offer_revisions(id),
 version integer NOT NULL, filename varchar(160) NOT NULL, content bytea NOT NULL, sha256 char(64) NOT NULL,
 state varchar(12) NOT NULL CHECK (state IN ('DRAFT','SENT','ACCEPTED','SUPERSEDED')),
 created_by varchar(254) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz,
 accepted_by uuid REFERENCES customers(id), accepted_at timestamptz, UNIQUE(booking_id,version)
);
CREATE INDEX contract_documents_booking_idx ON contract_documents(booking_id,version DESC);
