-- Voice call recording (core/voice's LiveKitEgressService) - tracks a
-- reference to each LiveKit Egress recording started for a self-hosted
-- voice call, so it can later be located/played back from the app's shared
-- S3/MinIO bucket (object_key). Created in every tenant schema regardless
-- of whether recording is actually turned on (livekit.egress.enabled,
-- default false) - same "table always exists, a feature flag decides
-- whether it's ever written to" posture as whatsapp_qr_connection
-- (V27__whatsapp_qr_connection.sql) and other opt-in features in this
-- codebase.
CREATE TABLE voice_call_recording (
    id           UUID PRIMARY KEY,
    agent_id     UUID NOT NULL,
    room_name    VARCHAR(255) NOT NULL,
    egress_id    VARCHAR(128),
    object_key   VARCHAR(512) NOT NULL,
    status       VARCHAR(16) NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    ended_at     TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

-- Looked up by room name on every recording/start (idempotency) and
-- recording/stop (locate the in-flight egress) call - see
-- VoiceCallRecordingRepository#findFirstByRoomNameAndStatusInOrderByCreatedAtDesc.
CREATE INDEX idx_voice_call_recording_room_name ON voice_call_recording (room_name);

-- For a future "recordings for this agent" console listing.
CREATE INDEX idx_voice_call_recording_agent_id ON voice_call_recording (agent_id);
