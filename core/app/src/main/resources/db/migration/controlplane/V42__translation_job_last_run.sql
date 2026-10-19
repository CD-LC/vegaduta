-- #423 - "there should be a job to run and extract all the translations
-- through central llm, that I will pay for it". TranslationJobSettings was
-- previously informational-only (see its javadoc); these columns back the
-- new real "Run translation job now" action (TranslationRunService), which
-- dispatches the existing .github/workflows/translate-messages.yml via the
-- GitHub REST API instead of only ever firing on its own weekly cron.
ALTER TABLE control_plane.translation_job_settings
    ADD COLUMN last_triggered_at TIMESTAMPTZ,
    ADD COLUMN last_triggered_by VARCHAR(255),
    ADD COLUMN last_trigger_status VARCHAR(32),
    ADD COLUMN last_trigger_message VARCHAR(1000);
