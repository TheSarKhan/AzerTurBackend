CREATE TABLE staff_users (
 id uuid PRIMARY KEY, email varchar(254) NOT NULL UNIQUE, name varchar(120) NOT NULL,
 password_hash varchar(255) NOT NULL, role varchar(12) NOT NULL CHECK(role IN ('ADMIN','OPERATOR')),
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), last_login_at timestamptz
);
CREATE TABLE staff_sessions (
 token_hash varchar(64) PRIMARY KEY, staff_id uuid NOT NULL REFERENCES staff_users(id) ON DELETE CASCADE,
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX staff_sessions_staff_idx ON staff_sessions(staff_id);
