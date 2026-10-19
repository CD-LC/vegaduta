-- Simple mode's Quick Task feature delivers its result to the logged-in
-- user's own phone via WhatsApp/SMS/a call - this is that number, captured
-- once (AccountController) rather than typed per task. Nullable: existing
-- users have none until they set it.
ALTER TABLE control_plane.app_user
    ADD COLUMN phone_number VARCHAR(32);
