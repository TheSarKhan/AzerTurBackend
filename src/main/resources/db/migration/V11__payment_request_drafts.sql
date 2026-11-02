ALTER TABLE staged_payment_requests ADD COLUMN customer_message varchar(2000) NOT NULL DEFAULT '';
CREATE TABLE payment_request_drafts (
 booking_id uuid PRIMARY KEY REFERENCES bookings(id),
 version bigint NOT NULL CHECK(version>0), preview_version bigint NOT NULL DEFAULT 0,
 payload text NOT NULL, updated_by varchar(254) NOT NULL, updated_at timestamptz NOT NULL DEFAULT now()
);
