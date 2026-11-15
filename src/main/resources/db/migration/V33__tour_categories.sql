-- Which categories a journey belongs to, as shown on the website and in the app. Stored as a comma
-- separated list. Existing journeys get the categories the website already derived from their
-- picture; Medical and Business have no journeys yet and stay empty until the team adds some.
ALTER TABLE tours ADD COLUMN categories varchar(120) NOT NULL DEFAULT '';

UPDATE tours SET categories = CASE image
  WHEN 'baku' THEN 'history,culture,entertainment'
  WHEN 'sheki' THEN 'history,culture,nature'
  WHEN 'gabala' THEN 'nature'
  WHEN 'mountains' THEN 'nature'
  ELSE ''
END;
