ALTER TABLE tours
  ADD COLUMN latitude double precision,
  ADD COLUMN longitude double precision,
  ADD CONSTRAINT tours_valid_map_coordinates CHECK (
    (latitude IS NULL AND longitude IS NULL) OR
    (latitude IS NOT NULL AND longitude IS NOT NULL
      AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
  );

-- These are representative destinations on the itinerary, not confirmed meeting points.
WITH tour_destinations(latitude, longitude, slugs) AS (
  VALUES
    -- OpenStreetMap: Maiden Tower, Fountains Square and Heydar Aliyev Center.
    (40.36615, 49.83727, ARRAY['baku-discovery', 'baku-old-city-walk', 'baku-caspian-promenade']),
    (40.37083, 49.83694, ARRAY['baku-tea-and-bazaars', 'baku-baku-weekend']),
    (40.39510, 49.86666, ARRAY['baku-architecture-walk']),
    -- Quba city, Nizami Park and Khinalug residential area (OSM way 274225475).
    (41.36150, 48.51420, ARRAY['quba-apple-country']),
    (41.36309, 48.50421, ARRAY['quba-forest-paths']),
    (41.17883, 48.12082, ARRAY['quba-khinalug', 'quba-khinalug-village', 'quba-highland-photography', 'quba-mountain-guesthouse']),
    -- Sahil Nohur, Gabaland and Gabala Nizami Park.
    (40.95517, 47.87650, ARRAY['gabala-lake-day', 'gabala-forest-picnic', 'caucasus-lakes-and-palaces']),
    (41.00217, 47.87760, ARRAY['gabala-foothill-views', 'gabala-family-weekend', 'caucasus-tea-and-trails']),
    (40.98254, 47.85010, ARRAY['gabala-slow-gabala', 'caucasus-market-to-mountains']),
    -- Sheki Khan's Palace and Upper Caravanserai.
    (41.20442, 47.19759, ARRAY['sheki-gabala', 'sheki-palace-courtyards', 'sheki-silk-road-stories', 'caucasus-grand-silk-road']),
    (41.20096, 47.19406, ARRAY['sheki-craft-lanes', 'sheki-sweets-and-tea', 'sheki-caravanserai-weekend', 'caucasus-craft-and-countryside'])
)
UPDATE tours AS tour
SET latitude = destination.latitude, longitude = destination.longitude
FROM tour_destinations AS destination
WHERE tour.slug = ANY(destination.slugs);
