-- Sarvam Integration Build Plan Stage 3: lightweight call-volume/outcome log
-- for the five non-chat capability clients (translate/speech-to-text/
-- text-to-speech/transliteration/language-ID) - a separate table from
-- llm_call_log (V52) since these aren't token/cost chat calls. See
-- SarvamCallLog's javadoc for the full reasoning.

CREATE TABLE sarvam_call_log (
    id            UUID PRIMARY KEY,
    capability    VARCHAR(32) NOT NULL,
    model         VARCHAR(64),
    success       BOOLEAN NOT NULL,
    error_message TEXT,
    latency_ms    BIGINT NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_sarvam_call_log_started_at ON sarvam_call_log (started_at DESC);
