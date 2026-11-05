ALTER TABLE quotes ADD COLUMN payload text;
CREATE TABLE customers (
 id uuid PRIMARY KEY, email varchar(254) NOT NULL UNIQUE, name varchar(100) NOT NULL,
 password_hash varchar(255) NOT NULL, locale varchar(2) NOT NULL CHECK(locale IN ('en','ru','az')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE customer_sessions (
 token_hash varchar(64) PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customers(id),
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX customer_sessions_customer_idx ON customer_sessions(customer_id);
CREATE TABLE bookings (
 id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customers(id), quote_id uuid NOT NULL REFERENCES quotes(id),
 status varchar(32) NOT NULL CHECK(status IN ('PENDING_REVIEW','AWAITING_PAYMENT','CONFIRMED','REJECTED','CANCELLED')),
 currency varchar(3) NOT NULL CHECK(currency IN ('EUR','USD','AZN')), total integer NOT NULL CHECK(total>0),
 deposit integer NOT NULL CHECK(deposit>0 AND deposit<=total), paid integer NOT NULL DEFAULT 0 CHECK(paid>=0 AND paid<=total),
 departure date NOT NULL, title varchar(160) NOT NULL, quote_payload text NOT NULL,
 customer_note varchar(2000) NOT NULL DEFAULT '', operator_note varchar(2000) NOT NULL DEFAULT '',
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(customer_id,quote_id)
);
CREATE INDEX bookings_customer_idx ON bookings(customer_id,created_at DESC);
CREATE INDEX bookings_status_idx ON bookings(status,created_at DESC);
CREATE TABLE booking_payments (
 id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id), customer_id uuid NOT NULL REFERENCES customers(id),
 idempotency_key varchar(80) NOT NULL, kind varchar(16) NOT NULL CHECK(kind IN ('DEPOSIT','BALANCE')),
 amount integer NOT NULL CHECK(amount>0), currency varchar(3) NOT NULL, provider varchar(20) NOT NULL DEFAULT 'DEMO',
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(customer_id,idempotency_key)
);
CREATE TABLE booking_requests (
 id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id), customer_id uuid NOT NULL REFERENCES customers(id),
 kind varchar(10) NOT NULL CHECK(kind IN ('CHANGE','CANCEL')), message varchar(2000) NOT NULL,
 status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','DECLINED')),
 response varchar(2000) NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now(), resolved_at timestamptz
);
CREATE UNIQUE INDEX one_pending_booking_request ON booking_requests(booking_id) WHERE status='PENDING';
CREATE TABLE booking_audit (
 id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id), actor varchar(254) NOT NULL,
 action varchar(40) NOT NULL, note varchar(2000) NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE notification_outbox (
 id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customers(id), booking_id uuid REFERENCES bookings(id),
 channel varchar(12) NOT NULL CHECK(channel IN ('EMAIL','WHATSAPP','PUSH')),
 event varchar(40) NOT NULL, body varchar(2000) NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'WAITING_CONFIGURATION', attempts integer NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz
);
CREATE INDEX notification_outbox_status_idx ON notification_outbox(status,created_at);
