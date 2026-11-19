package com.agenticai.core.voice;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.logging.LogSafe;
import com.agenticai.core.voice.dto.VoiceCallRecordingResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Persistence and egress orchestration behind {@link VoiceCallRecordingController}. Deliberately not
 * one big {@code @Transactional}: the egress calls are network I/O, and the "failed" row written on
 * an egress error must survive the re-thrown exception, so each repository save commits on its own.
 */
@ConditionalOnEditionFeature(EditionFeature.VOICE_REALTIME_SHARE)
@Service
public class VoiceCallRecordingService {

    private static final Logger log = LoggerFactory.getLogger(VoiceCallRecordingService.class);

    private static final List<VoiceCallRecordingStatus> IN_FLIGHT_STATUSES =
            List.of(
                    VoiceCallRecordingStatus.STARTING,
                    VoiceCallRecordingStatus.ACTIVE,
                    VoiceCallRecordingStatus.STOPPING);

    private final LiveKitEgressService egressService;
    private final VoiceCallRecordingRepository recordingRepository;

    public VoiceCallRecordingService(
            LiveKitEgressService egressService, VoiceCallRecordingRepository recordingRepository) {
        this.egressService = egressService;
        this.recordingRepository = recordingRepository;
    }

    /** The in-flight recording of the room, if any. */
    public Optional<VoiceCallRecordingResponse> findInFlight(String roomName) {
        return recordingRepository
                .findFirstByRoomNameAndStatusInOrderByCreatedAtDesc(roomName, IN_FLIGHT_STATUSES)
                .map(VoiceCallRecordingService::toResponse);
    }

    /** Starts a new egress recording of the room and persists it. */
    public VoiceCallRecordingResponse start(UUID agentId, String roomName, String tenantId) {
        LiveKitEgressService.EgressStartResult result;
        VoiceCallRecording recording;
        try {
            result = egressService.startRoomRecording(tenantId, roomName);
            recording =
                    new VoiceCallRecording(
                            agentId, roomName, result.egressId(), result.objectKey());
            recording.markActive();
        } catch (LiveKitEgressException e) {
            log.warn(
                    "Failed to start LiveKit egress for agent {} room {}: {}",
                    agentId,
                    LogSafe.clean(roomName),
                    e.getMessage());
            // Persist the failure too, so "never started" is distinguishable from a missing row.
            // Re-thrown: a genuine infra failure (LiveKit or the egress worker down) is a 500.
            recording = new VoiceCallRecording(agentId, roomName, null, "");
            recording.markFailed();
            recordingRepository.save(recording);
            throw e;
        }
        recordingRepository.save(recording);
        log.info(
                "Started call recording agent={} room={} egressId={}",
                agentId,
                LogSafe.clean(roomName),
                recording.getEgressId());
        return toResponse(recording);
    }

    /** Stops the agent's in-flight recording of the room; empty when there is none. */
    public Optional<VoiceCallRecordingResponse> stop(UUID agentId, String roomName) {
        var recordingOpt =
                recordingRepository
                        .findFirstByRoomNameAndStatusInOrderByCreatedAtDesc(
                                roomName, IN_FLIGHT_STATUSES)
                        .filter(r -> agentId.equals(r.getAgentId()));
        if (recordingOpt.isEmpty()) {
            return Optional.empty();
        }
        VoiceCallRecording recording = recordingOpt.get();
        recording.markStopping();
        try {
            egressService.stopRecording(recording.getEgressId());
            recording.markCompleted();
        } catch (LiveKitEgressException e) {
            log.warn(
                    "Failed to stop LiveKit egress {} for room {}: {}",
                    recording.getEgressId(),
                    LogSafe.clean(roomName),
                    e.getMessage());
            recording.markFailed();
        }
        recordingRepository.save(recording);
        log.info(
                "Stopped call recording agent={} room={} status={}",
                agentId,
                LogSafe.clean(roomName),
                recording.getStatus());
        return Optional.of(toResponse(recording));
    }

    private static VoiceCallRecordingResponse toResponse(VoiceCallRecording recording) {
        return new VoiceCallRecordingResponse(
                recording.getId(),
                recording.getRoomName(),
                recording.getStatus().name(),
                recording.getStartedAt(),
                recording.getEndedAt());
    }
}
