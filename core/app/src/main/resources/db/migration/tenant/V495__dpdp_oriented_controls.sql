-- DPDP-oriented controls (docs/compliance/dpdp-obligations.md). Engineering controls only;
-- the legal reading of every obligation is to be confirmed by counsel.
--
-- Four small tables, all per tenant schema (so every row here is tenant-isolated by the schema
-- itself, like the rest of this directory):
--
--   privacy_consent_policy    per-agent opt-in: notice + consent step on channel conversations
--   privacy_consent_record    append-only ledger of consent decisions per contact (channel, external id)
--   privacy_retention_policy  per tenant / per channel retention periods per data class
--   privacy_purpose_tag       purpose tags on knowledge collections and agents
--
-- privacy_consent_record deliberately has NO foreign key to agent: the ledger is the proof that a
-- notice was given and consent recorded (Act s.6(10)) and must outlive the agent that asked.

CREATE TABLE privacy_consent_policy (
    agent_id            UUID PRIMARY KEY REFERENCES agent (id) ON DELETE CASCADE,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    purpose             VARCHAR(64)  NOT NULL DEFAULT 'support',
    -- Bumped by the application whenever any notice text changes; a recorded consent only counts
    -- while its notice_version equals this one, so a changed notice re-asks.
    notice_version      INTEGER      NOT NULL DEFAULT 1,
    default_language    VARCHAR(16)  NOT NULL DEFAULT 'en',
    -- {"en": "...", "hi": "..."}: the full notice text per language (itemised data, purpose,
    -- how to withdraw / exercise rights / complain to the Board - Rules, rule 3).
    notices             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- Optional per-language "how to answer" line appended to the notice.
    instructions        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- Optional {"accept": [...], "decline": [...], "withdraw": [...]} overrides of the built-in words.
    keywords            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- Act s.6(6) / s.8(7)(a): on withdrawal, erase the contact's data unless the tenant has
    -- decided otherwise (legal retention duties are the tenant's to assess).
    erase_on_withdrawal BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_by          VARCHAR(255),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE privacy_consent_record (
    id               UUID PRIMARY KEY,
    -- Orders decisions that share a timestamp.
    seq              BIGINT GENERATED ALWAYS AS IDENTITY,
    channel          VARCHAR(16)  NOT NULL,
    external_id      VARCHAR(64)  NOT NULL,
    agent_id         UUID,
    purpose          VARCHAR(64)  NOT NULL,
    notice_version   INTEGER      NOT NULL,
    notice_language  VARCHAR(16)  NOT NULL,
    -- SHA-256 of the exact notice text shown, so "which words did they agree to" stays answerable
    -- after the policy text is edited.
    notice_sha256    VARCHAR(64),
    -- PROMPTED (notice sent), GRANTED, DECLINED, WITHDRAWN.
    decision         VARCHAR(16)  NOT NULL,
    decided_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_privacy_consent_record_subject
    ON privacy_consent_record (channel, external_id, purpose, seq DESC);

CREATE TABLE privacy_retention_policy (
    -- '*' = the tenant default; otherwise a ChannelType name, or VOICE for call recordings.
    channel         VARCHAR(16)  NOT NULL,
    -- TRANSCRIPT, RECORDING, EXTRACTED_FIELDS.
    data_class      VARCHAR(24)  NOT NULL,
    -- Opt-in per row: nothing is ever purged by a row that does not exist or is not enabled.
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    retention_days  INTEGER      NOT NULL CHECK (retention_days >= 1),
    updated_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (channel, data_class)
);

CREATE TABLE privacy_purpose_tag (
    -- KNOWLEDGE_COLLECTION or AGENT.
    resource_type  VARCHAR(24)  NOT NULL,
    resource_id    UUID         NOT NULL,
    purpose        VARCHAR(64)  NOT NULL,
    tagged_by      VARCHAR(255),
    tagged_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_type, resource_id, purpose)
);

CREATE INDEX idx_privacy_purpose_tag_resource ON privacy_purpose_tag (resource_type, resource_id);
