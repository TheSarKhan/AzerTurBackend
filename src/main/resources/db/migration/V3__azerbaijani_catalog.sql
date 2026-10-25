ALTER TABLE tours ADD COLUMN title_az varchar(160) NOT NULL DEFAULT '';
UPDATE tours SET title_az='Dağlara doğru' WHERE slug='quba-khinalug';
UPDATE tours SET title_az='İpək yolu boyunca' WHERE slug='sheki-gabala';
UPDATE tours SET title_az='Təzadlar şəhəri' WHERE slug='baku-discovery';
