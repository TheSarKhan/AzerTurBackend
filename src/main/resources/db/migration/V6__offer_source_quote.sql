ALTER TABLE offer_revisions ADD COLUMN source_quote_id uuid REFERENCES quotes(id);
