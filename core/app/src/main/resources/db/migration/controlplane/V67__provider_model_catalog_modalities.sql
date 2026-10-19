-- Direct feedback: "consumers should be able to attach audio and other files
-- if agents model supports those files in chat" - i.e. the upload control in
-- chat must be gated per-agent on whether that agent's actual provider+model
-- accepts image/audio/video input, not shown as a blanket "attach anything"
-- button. This adds the three modality flags ProviderModelCatalogService
-- needs to answer that question, alongside the model-id/display-name/
-- context-window columns V66 already added.
--
-- Defaulted to false (text-only) so a provider that isn't explicitly known to
-- support a modality never over-promises what a model can accept - see
-- ProviderModelCatalogService's SEED_MODELS javadoc for how each provider's
-- flags were determined.
ALTER TABLE control_plane.provider_model_catalog
    ADD COLUMN supports_image_input BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN supports_audio_input BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN supports_video_input BOOLEAN NOT NULL DEFAULT FALSE;
