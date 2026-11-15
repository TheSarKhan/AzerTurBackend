-- The tour region is shown in the apps; items are separated with a comma.
UPDATE tours SET region = replace(region, ' · ', ', ') WHERE region LIKE '%·%';
