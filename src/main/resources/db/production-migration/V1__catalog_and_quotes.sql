CREATE TABLE tours (
 slug varchar(80) PRIMARY KEY, title_en varchar(160) NOT NULL, title_ru varchar(160) NOT NULL,
 region varchar(160) NOT NULL, days integer NOT NULL CHECK(days BETWEEN 1 AND 14),
 from_price_eur integer NOT NULL CHECK(from_price_eur > 0), image varchar(80) NOT NULL
);
CREATE TABLE quotes (
 id uuid PRIMARY KEY, tour_slug varchar(80) NOT NULL REFERENCES tours(slug), currency varchar(3) NOT NULL,
 total integer NOT NULL CHECK(total > 0), deposit integer NOT NULL CHECK(deposit > 0),
 departure date NOT NULL, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL
);
CREATE INDEX quotes_created_at_idx ON quotes(created_at DESC);
INSERT INTO tours VALUES
 ('quba-khinalug','Into the highlands','Навстречу горам','Quba · Khinalug',2,240,'mountains'),
 ('sheki-gabala','Along the Silk Road','По Шёлковому пути','Sheki · Gabala',3,410,'sheki'),
 ('baku-discovery','A city of contrasts','Город контрастов','Baku · Old City',1,85,'baku');
