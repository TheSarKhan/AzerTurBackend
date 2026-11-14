-- Customers cannot erase their own history: bookings, contracts and payments must be kept for the office.
-- A deletion request is recorded here and handled by the office.
CREATE TABLE account_deletion_requests(
  id uuid PRIMARY KEY,
  customer_id uuid NOT NULL UNIQUE REFERENCES customers(id),
  state varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','DONE','REJECTED')),
  requested_at timestamptz NOT NULL DEFAULT now(),
  handled_at timestamptz
);
