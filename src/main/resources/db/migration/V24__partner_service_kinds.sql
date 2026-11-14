-- Partners can now offer every service kind the offers use: region-to-region transfers, events,
-- meals, entrance tickets and a photographer, next to the original five.
ALTER TABLE partner_services DROP CONSTRAINT partner_services_service_key_check;
ALTER TABLE partner_services ADD CONSTRAINT partner_services_service_key_check
  CHECK (service_key IN ('hotel','transport','guide','transfer','intercity','activity','event','meals','tickets','photographer'));
