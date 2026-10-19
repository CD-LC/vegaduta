-- Generalizes hosted-pool row activation beyond a pasted API key, so an
-- admin can also activate a row against Google Vertex AI without hand-
-- editing deploy/.env: either Application Default Credentials (no secret
-- stored at all - the running container must already have ADC available) or
-- a pasted GCP service-account JSON key (encrypted and stored in the
-- existing encrypted_api_key column, same posture as a plain API key -
-- see com.agenticai.core.providers.VertexCredential for the same
-- "reuse the one TEXT column" call already made for tenant BYOK Vertex).
--
-- auth_type: NOT NULL DEFAULT 'API_KEY' so every pre-existing row keeps
-- behaving exactly as before with no backfill needed. Plain text, not an
-- enum/CHECK constraint - see PlatformHostedLlmAuthType's javadoc for why
-- (an unrecognized value must never fail to load a row).
--
-- vertex_project_id / vertex_location: nullable - only meaningful when
-- auth_type is one of the two Vertex types. vertex_location's DEFAULT here
-- only applies to rows inserted without the application specifying a value;
-- PlatformHostedLlmProviderService additionally defaults a blank
-- vertex_location to 'us-central1' at the application layer.
--
-- encrypted_api_key DROP NOT NULL: a VERTEX_ADC row stores no secret at all
-- (ADC is resolved from the environment, not the database), so the column
-- that has held every hosted-pool secret since V58 must become optional
-- rather than gaining a second nullable secret column.
--
-- control_plane-schema-qualified, same as the table it extends (see V58 / V85 / V122 / V136).
ALTER TABLE control_plane.platform_hosted_llm_provider
    ADD COLUMN auth_type         VARCHAR(32) NOT NULL DEFAULT 'API_KEY',
    ADD COLUMN vertex_project_id VARCHAR(128),
    ADD COLUMN vertex_location   VARCHAR(64) DEFAULT 'us-central1',
    ALTER COLUMN encrypted_api_key DROP NOT NULL;
