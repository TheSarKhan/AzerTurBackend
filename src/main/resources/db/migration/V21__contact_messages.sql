-- Messages sent from the public contact form. Staff read and close them in the dashboard;
-- email_state records the best-effort copy sent to the team inbox (CONTACT_INBOX).
CREATE TABLE contact_messages (
 id uuid PRIMARY KEY,
 name varchar(80) NOT NULL,
 email varchar(254) NOT NULL,
 topic varchar(10) NOT NULL CHECK (topic IN ('general','custom','group','partner')),
 message varchar(2000) NOT NULL,
 locale varchar(2) NOT NULL DEFAULT 'en' CHECK (locale IN ('en','ru','az')),
 state varchar(10) NOT NULL DEFAULT 'NEW' CHECK (state IN ('NEW','HANDLED')),
 email_state varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (email_state IN ('PENDING','SENT','SKIPPED','FAILED')),
 created_at timestamptz NOT NULL DEFAULT now(),
 handled_by varchar(254),
 handled_at timestamptz
);
CREATE INDEX contact_messages_state ON contact_messages(state, created_at DESC);
