CREATE TABLE customer_identities (
 provider varchar(16) NOT NULL CHECK(provider IN ('GOOGLE')), subject varchar(255) NOT NULL,
 customer_id uuid NOT NULL REFERENCES customers(id) ON DELETE CASCADE, email varchar(254) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(provider,subject)
);
CREATE INDEX customer_identities_customer_idx ON customer_identities(customer_id);
