-- An operator can close an open payment request as unsuccessful (the customer did not pay,
-- the transfer bounced) or withdraw it, with a short note. Successful payments are still
-- recorded through mark-paid.
ALTER TABLE staged_payment_requests DROP CONSTRAINT staged_payment_requests_status_check;
ALTER TABLE staged_payment_requests
 ADD CONSTRAINT staged_payment_requests_status_check CHECK (status IN ('OPEN','PAID','CANCELLED','FAILED')),
 ADD COLUMN closed_at timestamptz,
 ADD COLUMN closed_by varchar(254),
 ADD COLUMN close_note varchar(500);
DROP INDEX one_staged_deposit;
CREATE UNIQUE INDEX one_staged_deposit ON staged_payment_requests(booking_id)
 WHERE kind='DEPOSIT' AND status NOT IN ('CANCELLED','FAILED');
