package com.agenticai.core.voice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceCallRecordingRepository extends JpaRepository<VoiceCallRecording, UUID> {

    /** Most recent still-in-flight (STARTING/ACTIVE/STOPPING) recording for a room, if any - used to make {@code recording/start} idempotent and to locate what {@code recording/stop} should stop. */
    Optional<VoiceCallRecording> findFirstByRoomNameAndStatusInOrderByCreatedAtDesc(
        String roomName, List<VoiceCallRecordingStatus> statuses
    );

    List<VoiceCallRecording> findByAgentIdOrderByCreatedAtDesc(UUID agentId);
}
