CREATE TABLE cancellation_assessments (
 request_id uuid PRIMARY KEY REFERENCES booking_requests(id),
 version bigint NOT NULL CHECK(version>0), contract_id uuid NOT NULL REFERENCES contract_documents(id),
 source_quote_id uuid NOT NULL REFERENCES quotes(id), paid_minor bigint NOT NULL CHECK(paid_minor>=0),
 payload text NOT NULL, state varchar(12) NOT NULL CHECK(state IN ('REVIEWED','APPROVED')),
 reviewed_by varchar(254) NOT NULL, reviewed_at timestamptz NOT NULL DEFAULT now(),
 approved_at timestamptz
);
