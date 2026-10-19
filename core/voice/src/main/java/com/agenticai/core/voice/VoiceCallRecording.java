package com.agenticai.core.voice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A reference to one LiveKit Egress recording of a self-hosted voice call
 * (see {@link LiveKitEgressService}, {@link VoiceCallController}) - tenant
 * schema, one row per room/call. Deliberately holds no audio itself, only
 * enough metadata to look the object up later in the app's shared S3/MinIO
 * bucket ({@code objectKey}) and to correlate it back to the agent/room it
 * came from.
 *
 * <p>This table is created in every tenant schema regardless of whether
 * recording is actually turned on - same "table always exists, a feature
 * flag decides whether it's ever written to" posture as every other opt-in
 * feature in this codebase (see {@code app.storage}/{@code code-intel}
 * blocks in {@code application.yml}). Recording itself is gated by {@code
 * livekit.egress.enabled} (default {@code false} - see {@link
 * LiveKitEgressService}'s javadoc for the consent/privacy reasoning behind
 * that default).
 */
@Entity
@Table(name = "voice_call_recording")
public class VoiceCallRecording {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** The LiveKit room name this recording covers - see {@code VoiceCallController#issueToken}'s {@code roomName} generation. */
    @Column(name = "room_name", nullable = false, length = 255)
    private String roomName;

    /** LiveKit's own egress identifier (the {@code egress_id} field of {@code EgressInfo}) - needed to call {@code StopEgress} later. Null only in the brief window between a start failure being recorded and... it never is; kept nullable defensively in case a future write path can't resolve it. */
    @Column(name = "egress_id", length = 128)
    private String egressId;

    /** The S3/MinIO object key this recording was (or will be) uploaded to - computed up-front by {@link LiveKitEgressService}, not read back from LiveKit, so it's known immediately even before the recording finishes. */
    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VoiceCallRecordingStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VoiceCallRecording() {
        // JPA
    }

    public VoiceCallRecording(UUID agentId, String roomName, String egressId, String objectKey) {
        this.agentId = agentId;
        this.roomName = roomName;
        this.egressId = egressId;
        this.objectKey = objectKey;
        this.status = VoiceCallRecordingStatus.STARTING;
        Instant now = Instant.now();
        this.startedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markActive() {
        this.status = VoiceCallRecordingStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void markStopping() {
        this.status = VoiceCallRecordingStatus.STOPPING;
        this.updatedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = VoiceCallRecordingStatus.COMPLETED;
        this.endedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = VoiceCallRecordingStatus.FAILED;
        this.endedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getRoomName() {
        return roomName;
    }

    public String getEgressId() {
        return egressId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public VoiceCallRecordingStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
