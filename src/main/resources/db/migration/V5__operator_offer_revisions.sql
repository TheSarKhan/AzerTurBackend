CREATE TABLE offer_drafts (
 booking_id uuid PRIMARY KEY REFERENCES bookings(id), version bigint NOT NULL CHECK(version>0),
 payload text NOT NULL, preview_version bigint NOT NULL DEFAULT 0,
 updated_by varchar(254) NOT NULL, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE offer_revisions (
 id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES bookings(id), revision integer NOT NULL CHECK(revision>0),
 draft_version bigint NOT NULL, payload text NOT NULL, sent_by varchar(254) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(booking_id,revision), UNIQUE(booking_id,draft_version)
);
CREATE INDEX offer_revisions_booking_idx ON offer_revisions(booking_id,revision DESC);
