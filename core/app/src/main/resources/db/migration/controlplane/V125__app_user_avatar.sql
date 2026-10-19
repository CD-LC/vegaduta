-- Profile-picture upload (tenant web app AppShell avatar, next to Sign out).
-- Stores only the MinIO/S3 object key the picture was uploaded under (e.g.
-- "avatars/<app_user_id>.png") - never the raw bytes in Postgres, same
-- "persist the key, not the blob" convention as generated_image.object_key
-- and sarvam_generated_audio.object_key. Nullable: a user has no picture
-- until they upload one, in which case AppShell falls back to their
-- initials, same as today.
ALTER TABLE control_plane.app_user
    ADD COLUMN avatar_url VARCHAR(512);
