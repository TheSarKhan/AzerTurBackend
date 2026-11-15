-- V29 joined the theme and the place with a middle dot in the sample event titles. The site never
-- uses that separator, so the samples now use a comma. V29 itself stays unchanged because it has
-- already been applied.
UPDATE events
SET title_en = replace(title_en, ' · ', ', '),
    title_ru = replace(title_ru, ' · ', ', '),
    title_az = replace(title_az, ' · ', ', '),
    updated_at = now()
WHERE created_by = 'catalog-seed'
  AND slug LIKE 'sample-%'
  AND (title_en LIKE '% · %' OR title_ru LIKE '% · %' OR title_az LIKE '% · %');
