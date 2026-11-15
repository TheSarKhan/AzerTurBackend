-- Starter catalog for both existing databases and fresh installations.
INSERT INTO tours (slug, title_en, title_ru, title_az, region, days, from_price_eur, image)
VALUES
  ('baku-old-city-walk', 'Old City courtyards', 'Дворики Старого города', 'İçərişəhərin həyətləri', 'Baku', 1, 65, 'baku'),
  ('baku-caspian-promenade', 'Caspian promenade', 'Каспийская набережная', 'Xəzər sahilində gəzinti', 'Baku', 1, 72, 'baku'),
  ('baku-architecture-walk', 'Baku architecture walk', 'Архитектурная прогулка по Баку', 'Bakının memarlıq marşrutu', 'Baku', 1, 89, 'baku'),
  ('baku-tea-and-bazaars', 'Baku tea and bazaars', 'Бакинский чай и базары', 'Bakının çay və bazarları', 'Baku', 2, 79, 'baku'),
  ('baku-baku-weekend', 'A weekend in Baku', 'Выходные в Баку', 'Bakıda həftəsonu', 'Baku', 2, 185, 'baku'),
  ('quba-apple-country', 'Quba apple country', 'Яблочные сады Губы', 'Qubanın alma bağları', 'Quba, Khinalug', 1, 105, 'mountains'),
  ('quba-forest-paths', 'Quba forest paths', 'Лесные тропы Губы', 'Qubanın meşə cığırları', 'Quba, Khinalug', 1, 115, 'mountains'),
  ('quba-khinalug-village', 'A day in Khinalug', 'День в Хыналыге', 'Xınalıqda bir gün', 'Quba, Khinalug', 1, 138, 'mountains'),
  ('quba-highland-photography', 'Highland photography weekend', 'Фототур в горах', 'Dağlarda foto həftəsonu', 'Quba, Khinalug', 2, 259, 'mountains'),
  ('quba-mountain-guesthouse', 'Mountain guesthouse escape', 'Отдых в горном гостевом доме', 'Dağ qonaq evində istirahət', 'Quba, Khinalug', 2, 289, 'mountains'),
  ('gabala-lake-day', 'Gabala lakeside day', 'День у озера в Габале', 'Qəbələdə göl kənarında bir gün', 'Gabala', 1, 112, 'gabala'),
  ('gabala-forest-picnic', 'Gabala forest picnic', 'Лесной пикник в Габале', 'Qəbələ meşəsində piknik', 'Gabala', 1, 123, 'gabala'),
  ('gabala-foothill-views', 'Gabala foothill views', 'Предгорья Габалы', 'Qəbələnin dağətəyi mənzərələri', 'Gabala', 1, 129, 'gabala'),
  ('gabala-family-weekend', 'Gabala family weekend', 'Семейные выходные в Габале', 'Qəbələdə ailəvi həftəsonu', 'Gabala', 2, 245, 'gabala'),
  ('gabala-slow-gabala', 'Two slow days in Gabala', 'Два неспешных дня в Габале', 'Qəbələdə iki sakit gün', 'Gabala', 2, 269, 'gabala'),
  ('sheki-palace-courtyards', 'Sheki palace courtyards', 'Дворцы и дворы Шеки', 'Şəkinin saray həyətləri', 'Sheki', 1, 145, 'sheki'),
  ('sheki-craft-lanes', 'Sheki artisan lanes', 'Ремесленные улочки Шеки', 'Şəkinin sənətkar küçələri', 'Sheki', 1, 152, 'sheki'),
  ('sheki-sweets-and-tea', 'Sheki sweets and tea', 'Сладости и чай в Шеки', 'Şəki şirniyyatı və çayı', 'Sheki', 1, 155, 'sheki'),
  ('sheki-caravanserai-weekend', 'Caravanserai weekend', 'Выходные у караван-сарая', 'Karvansarada həftəsonu', 'Sheki', 2, 295, 'sheki'),
  ('sheki-silk-road-stories', 'Silk Road stories in Sheki', 'Истории Шёлкового пути в Шеки', 'Şəkidə İpək yolu hekayələri', 'Sheki', 2, 315, 'sheki'),
  ('caucasus-lakes-and-palaces', 'Lakes and palaces', 'Озёра и дворцы', 'Göllər və saraylar', 'Gabala, Sheki', 2, 289, 'sheki'),
  ('caucasus-tea-and-trails', 'Tea and foothill trails', 'Чай и тропы предгорий', 'Çay və dağətəyi cığırlar', 'Gabala, Sheki', 2, 309, 'sheki'),
  ('caucasus-market-to-mountains', 'From market to mountains', 'От рынка к горам', 'Bazardan dağlara', 'Gabala, Sheki', 2, 329, 'sheki'),
  ('caucasus-craft-and-countryside', 'Craft and countryside', 'Ремёсла и загородный отдых', 'Sənətkarlıq və kənd istirahəti', 'Gabala, Sheki', 3, 395, 'sheki'),
  ('caucasus-grand-silk-road', 'Grand Silk Road route', 'Большой маршрут Шёлкового пути', 'Böyük İpək yolu marşrutu', 'Gabala, Sheki', 4, 485, 'sheki')
ON CONFLICT (slug) DO NOTHING;

-- Sample calendar entries use city-centre coordinates and illustrative photos.
-- The schedule begins on the migration date in Baku, with three waves over five months.
WITH ideas(category, key, title_en, title_ru, title_az, image_key, weekday, hour) AS (
  VALUES
    ('CONCERT', 'acoustic', 'Acoustic evening', 'Акустический вечер', 'Akustik musiqi axşamı', 'guesthouse', 0, 19),
    ('FESTIVAL', 'local-culture', 'Local culture day', 'День местной культуры', 'Yerli mədəniyyət günü', 'sheki', 1, 12),
    ('SPORT', 'outdoor-walk', 'Morning outdoor walk', 'Утренняя прогулка', 'Səhər yürüşü', 'hiking', 2, 9),
    ('CULTURE', 'stories', 'Heritage and stories', 'Истории и наследие', 'İrs və hekayələr', 'sheki', 3, 14),
    ('MARKET', 'handmade', 'Handmade market', 'Ярмарка ремёсел', 'Əl işləri yarmarkası', 'khinalug', 4, 11),
    ('FOOD', 'tea-table', 'Tea and local flavours', 'Чай и местные вкусы', 'Çay və yerli dadlar', 'tea', 5, 16),
    ('FAMILY', 'family-photo', 'Family photo walk', 'Семейная фотопрогулка', 'Ailəvi foto gəzinti', 'gabala', 6, 10),
    ('OTHER', 'sunset', 'Sunset sketching', 'Зарисовки на закате', 'Günbatımı eskizləri', 'baku', 7, 17)
), samples AS (
  SELECT c.*, i.*, wave,
    'sample-' || c.slug || '-' || i.key || '-' || wave AS event_slug,
    ((now() AT TIME ZONE 'Asia/Baku')::date + 1 + (wave - 1) * 63 + weekday
     + (array_position(ARRAY['baku','quba','gabala','sheki'], c.slug) - 1) * 2
     + make_time(hour, 0, 0)) AT TIME ZONE 'Asia/Baku' AS event_start
  FROM cities c CROSS JOIN ideas i CROSS JOIN generate_series(1, 3) AS wave
  WHERE c.slug IN ('baku', 'quba', 'gabala', 'sheki')
)
INSERT INTO events (
  id, slug, title_en, title_ru, title_az,
  description_en, description_ru, description_az, category, city_slug,
  venue_name, latitude, longitude, starts_at, ends_at, image,
  status, source, source_note, created_by, updated_by
)
SELECT md5(event_slug)::uuid, event_slug,
  title_en || ', ' || name_en || ' (Sample)',
  title_ru || ', ' || name_ru || ' (Пример)',
  title_az || ', ' || name_az || ' (Nümunə)',
  'Sample calendar entry for ' || name_en || '. The date and location illustrate how the calendar works.',
  'Пример для календаря: ' || name_ru || '. Дата и место приведены для демонстрации.',
  name_az || ' üçün təqvim nümunəsi. Tarix və məkan təqvimin işini göstərmək üçün verilib.',
  category, slug, name_az, latitude, longitude, event_start, event_start + interval '2 hours',
  'https://aztrip.online/images/homepage-v3/' ||
    image_key || '.webp',
  'PUBLISHED', 'ADMIN', 'Illustrative calendar entry', 'catalog-seed', 'catalog-seed'
FROM samples
ON CONFLICT (slug) DO NOTHING;
