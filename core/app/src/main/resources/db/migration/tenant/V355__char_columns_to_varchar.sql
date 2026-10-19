-- Hibernate schema validation rejects bpchar (CHAR(n)) columns mapped to String
-- (varchar). Fixed forward rather than editing V280/V346, which may already be
-- applied (checksum). Values are fixed-width ('YYYY-MM', ISO 4217), so the
-- conversion loses no padding.
ALTER TABLE mandate_ledger       ALTER COLUMN month_key TYPE VARCHAR(7);
ALTER TABLE payment_transaction  ALTER COLUMN currency  TYPE VARCHAR(3);
ALTER TABLE resolution_event     ALTER COLUMN currency  TYPE VARCHAR(3);
