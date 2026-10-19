ALTER TABLE twilio_credential
    ADD COLUMN api_key_sid VARCHAR(64),
    ADD COLUMN encrypted_api_key_secret TEXT;
