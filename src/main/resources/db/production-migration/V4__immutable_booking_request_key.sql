ALTER TABLE bookings ADD COLUMN original_quote_id uuid REFERENCES quotes(id);
UPDATE bookings SET original_quote_id=quote_id;
ALTER TABLE bookings ALTER COLUMN original_quote_id SET NOT NULL;
CREATE UNIQUE INDEX bookings_original_request_idx ON bookings(customer_id,original_quote_id);
