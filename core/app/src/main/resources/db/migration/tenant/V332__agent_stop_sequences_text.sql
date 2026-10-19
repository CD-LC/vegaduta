-- stop_sequences was created as JSONB by V331, but Hibernate maps List<String> JSON columns in a way
-- that breaks schema validation of unrelated ARRAY columns (provider_credential.capabilities), so the
-- entity now stores the list as a JSON string in TEXT via StringListJsonConverter. Convert in place;
-- a no-op-safe cast when the column is already TEXT.
ALTER TABLE agent ALTER COLUMN stop_sequences DROP DEFAULT;
ALTER TABLE agent ALTER COLUMN stop_sequences TYPE TEXT USING stop_sequences::text;
ALTER TABLE agent ALTER COLUMN stop_sequences SET DEFAULT '[]';
