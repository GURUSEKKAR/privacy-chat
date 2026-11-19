-- Public device fingerprints used to validate historical Add and Welcome messages.
ALTER TABLE mls_events ADD COLUMN roster_fingerprints JSONB;
