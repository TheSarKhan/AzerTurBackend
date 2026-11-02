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
