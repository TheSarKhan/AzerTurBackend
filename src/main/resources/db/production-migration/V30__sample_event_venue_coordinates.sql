-- The calendar entries remain samples. Their venue pins point to mapped locations.
WITH venues(city_slug, venue_index, venue_name, latitude, longitude) AS (
  VALUES
    -- OpenStreetMap: way 273101274, node 13018215782, GeoNames 8199680.
    ('baku', 0, 'Fəvvarələr meydanı', 40.37083, 49.83694),
    ('baku', 1, 'Heydər Əliyev Mərkəzi', 40.39510, 49.86666),
    ('baku', 2, 'Heydər Əliyev İdman və Konsert Kompleksi', 40.39204, 49.82502),
    -- OpenStreetMap nodes 244311432, 2786092320 and way 639309010.
    ('quba', 0, 'Nizami parkı', 41.36309, 48.50421),
    ('quba', 1, 'Qırmızı Qəsəbə', 41.37066, 48.51636),
    ('quba', 2, 'Quba şəhər mərkəzi', 41.36150, 48.51420),
    -- OpenStreetMap ways 187412115, 187146862, 673217218.
    ('gabala', 0, 'Sahil Nohur İstirahət Mərkəzi', 40.95517, 47.87650),
    ('gabala', 1, 'Qəbələnd', 41.00217, 47.87760),
    ('gabala', 2, 'Nizami parkı', 40.98254, 47.85010),
    -- OpenStreetMap way 272592237 and nodes 7976541514, 13914118018.
    ('sheki', 0, 'Şəki Xan sarayı', 41.20442, 47.19759),
    ('sheki', 1, 'Yuxarı Karvansaray', 41.20096, 47.19406),
    ('sheki', 2, 'Kiş körpüsü', 41.24516, 47.18892)
), theme_venue(category, venue_index) AS (
  VALUES ('CONCERT', 1), ('FESTIVAL', 2), ('SPORT', 2), ('CULTURE', 0),
         ('MARKET', 0), ('FOOD', 1), ('FAMILY', 1), ('OTHER', 2)
)
UPDATE events AS event
SET venue_name = venue.venue_name,
    latitude = venue.latitude,
    longitude = venue.longitude,
    version = event.version + 1,
    updated_at = now()
FROM theme_venue AS theme
JOIN venues AS venue ON venue.venue_index = theme.venue_index
WHERE event.created_by = 'catalog-seed'
  AND event.updated_by = 'catalog-seed'
  AND event.slug LIKE 'sample-%'
  AND event.city_slug = venue.city_slug
  AND event.category = theme.category
  AND (event.venue_name, event.latitude, event.longitude)
      IS DISTINCT FROM (venue.venue_name, venue.latitude, venue.longitude);
