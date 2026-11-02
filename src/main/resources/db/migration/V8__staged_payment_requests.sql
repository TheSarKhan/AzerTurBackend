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
