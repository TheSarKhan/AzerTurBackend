-- Office-recorded payments: a payment request is marked paid by finance staff when the
-- money has been received (bank transfer, cash, card terminal). Online payment will
-- fill the same columns once a payment provider is connected.
ALTER TABLE staged_payment_requests
 ADD COLUMN paid_at timestamptz,
 ADD COLUMN payment_method varchar(20)
  CHECK (payment_method IS NULL OR payment_method IN ('BANK_TRANSFER','CASH','CARD_TERMINAL','OTHER')),
 ADD COLUMN payment_reference varchar(120),
 ADD COLUMN recorded_by varchar(254);
ALTER TABLE staged_payment_requests
 ADD CONSTRAINT staged_paid_has_details
 CHECK (status<>'PAID' OR (paid_at IS NOT NULL AND payment_method IS NOT NULL AND recorded_by IS NOT NULL));
ALTER TABLE booking_payments ALTER COLUMN provider SET DEFAULT 'MANUAL';
