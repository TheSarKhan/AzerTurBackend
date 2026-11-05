-- Nearby events: a small city list and staff-managed events. Version 8.1 sorts after the production
-- bridge's V8 and before the full chain's V9, so it applies cleanly in both migration folders.
-- Independent of the booking tables so it can run on the production schema as it is today.
CREATE TABLE cities (
 slug varchar(40) PRIMARY KEY,
 name_en varchar(80) NOT NULL, name_ru varchar(80) NOT NULL, name_az varchar(80) NOT NULL,
 latitude numeric(9,6) NOT NULL CHECK(latitude BETWEEN -90 AND 90),
 longitude numeric(9,6) NOT NULL CHECK(longitude BETWEEN -180 AND 180)
);
INSERT INTO cities(slug,name_en,name_ru,name_az,latitude,longitude) VALUES
 ('baku','Baku','Баку','Bakı',40.409300,49.867100),
 ('quba','Quba','Куба','Quba',41.361100,48.512600),
 ('gabala','Gabala','Габала','Qəbələ',40.978590,47.848900),
 ('sheki','Sheki','Шеки','Şəki',41.191900,47.170600);
CREATE TABLE events (
 id uuid PRIMARY KEY,
 slug varchar(120) NOT NULL UNIQUE,
 title_en varchar(160) NOT NULL, title_ru varchar(160), title_az varchar(160) NOT NULL,
 description_en varchar(4000) NOT NULL DEFAULT '', description_ru varchar(4000), description_az varchar(4000) NOT NULL DEFAULT '',
 category varchar(12) NOT NULL CHECK(category IN ('CONCERT','FESTIVAL','SPORT','CULTURE','MARKET','FOOD','FAMILY','OTHER')),
 city_slug varchar(40) NOT NULL REFERENCES cities(slug),
 venue_name varchar(160) NOT NULL, address varchar(240) NOT NULL DEFAULT '',
 latitude numeric(9,6) NOT NULL CHECK(latitude BETWEEN -90 AND 90),
 longitude numeric(9,6) NOT NULL CHECK(longitude BETWEEN -180 AND 180),
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
 time_known boolean NOT NULL DEFAULT true,
 is_free boolean NOT NULL DEFAULT false,
 price_from_minor integer CHECK(price_from_minor IS NULL OR price_from_minor>=0),
 currency varchar(3) NOT NULL DEFAULT 'AZN' CHECK(currency IN ('AZN','EUR','USD')),
 ticket_url varchar(500) CHECK(ticket_url IS NULL OR ticket_url LIKE 'https://%'),
 image varchar(500) CHECK(image IS NULL OR image LIKE 'https://%'),
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PENDING_REVIEW','PUBLISHED','CANCELLED')),
 source varchar(8) NOT NULL DEFAULT 'ADMIN' CHECK(source IN ('ADMIN','EXTERNAL')),
 external_source varchar(40), external_id varchar(120),
 admin_locked boolean NOT NULL DEFAULT false,
 source_note varchar(500) NOT NULL DEFAULT '',
 version integer NOT NULL DEFAULT 0,
 created_by varchar(254) NOT NULL, updated_by varchar(254) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(ends_at >= starts_at),
 CHECK((external_source IS NULL) = (external_id IS NULL))
);
CREATE INDEX events_status_start_idx ON events(status, starts_at);
CREATE INDEX events_city_start_idx ON events(city_slug, starts_at);
CREATE INDEX events_geo_idx ON events(latitude, longitude);
CREATE UNIQUE INDEX events_external_idx ON events(external_source, external_id) WHERE external_source IS NOT NULL;
-- Kept after an event is deleted, so it has no foreign key.
CREATE TABLE event_audit (
 id uuid PRIMARY KEY, event_id uuid NOT NULL, actor varchar(254) NOT NULL,
 action varchar(16) NOT NULL, note varchar(2000) NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX event_audit_event_idx ON event_audit(event_id, created_at);
