package com.agenticai.core.voice;

/**
 * Lifecycle of a {@link VoiceCallRecording} - deliberately mirrors LiveKit's
 * own {@code EgressStatus} enum loosely (EGRESS_STARTING/EGRESS_ACTIVE/
 * EGRESS_ENDING/EGRESS_COMPLETE/EGRESS_FAILED) rather than copying it
 * verbatim, since this table only needs enough detail to answer "is there a
 * recording in flight for this room" and "can this recording be played back
 * yet" - not the full egress state machine.
 */
public enum VoiceCallRecordingStatus {
    /** {@code StartRoomCompositeEgress} call succeeded; LiveKit has accepted the recording request. */
    STARTING,
    /** Recording is confirmed in progress (best-effort - this codebase does not yet consume LiveKit's egress webhooks, so this is set optimistically on start). */
    ACTIVE,
    /** {@code StopEgress} was called; waiting for LiveKit to finish flushing/uploading the file. */
    STOPPING,
    /** Recording finished and (per LiveKit's own upload behavior) the object should be in the configured S3/MinIO bucket at {@code objectKey}. */
    COMPLETED,
    /** The Egress API call itself failed (LiveKit unreachable, bad request, etc.) - {@code objectKey} was reserved but nothing was ever recorded. */
    FAILED
}
