-- Storage migration Stage 3 - direct feedback: "come up with strategy where
-- to store all these video and which is the best platform". base64-in-
-- Postgres (V62) doesn't scale to video/large files; new rows now go to
-- S3-compatible object storage (FileStorageService, core/storage) instead,
-- with only a pointer key stored here.
--
-- audio_data becomes nullable (not dropped) rather than backfilled/migrated -
-- every pre-existing row keeps working exactly as before through the same
-- base64 decode path (SarvamGeneratedAudioController falls back to it when
-- object_key is null), so this migration needs no data-migration script or
-- S3 upload of historical rows. New rows going forward always populate
-- object_key and leave audio_data null - see SarvamGeneratedAudioService.
ALTER TABLE sarvam_generated_audio
    ADD COLUMN object_key VARCHAR(512),
    ALTER COLUMN audio_data DROP NOT NULL;
