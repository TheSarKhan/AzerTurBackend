ALTER TABLE customers ADD COLUMN email_verified_at timestamptz;
ALTER TABLE customer_sessions ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE customer_sessions ADD CONSTRAINT customer_sessions_id_unique UNIQUE(id);
ALTER TABLE customer_sessions ADD COLUMN device varchar(100) NOT NULL DEFAULT 'Other device';
ALTER TABLE customer_sessions ADD COLUMN last_seen_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE account_links (
 id uuid PRIMARY KEY,customer_id uuid NOT NULL REFERENCES customers(id),purpose varchar(8) NOT NULL CHECK(purpose IN ('VERIFY','RESET')),
 token_hash char(64) NOT NULL UNIQUE,expires_at timestamptz NOT NULL,consumed_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX account_links_customer_idx ON account_links(customer_id,purpose,created_at DESC);
CREATE TABLE account_mail_jobs (
 id uuid PRIMARY KEY,customer_id uuid REFERENCES customers(id),link_id uuid REFERENCES account_links(id),
 purpose varchar(20) NOT NULL CHECK(purpose IN ('VERIFY','RESET_REQUEST','RESET','PASSWORD_CHANGED')),
 cipher bytea,nonce bytea,state varchar(24) NOT NULL CHECK(state IN ('QUEUED','WAITING_CONFIGURATION','SENDING','SENT','UNKNOWN','FAILED','REVOKED','RESOLVED')),
 attempts integer NOT NULL DEFAULT 0,next_attempt_at timestamptz NOT NULL DEFAULT now(),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE customer_security_audit(id uuid PRIMARY KEY,customer_id uuid NOT NULL REFERENCES customers(id),action varchar(40) NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
