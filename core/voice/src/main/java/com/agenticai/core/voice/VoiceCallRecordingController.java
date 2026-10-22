package com.agenticai.core.voice;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.voice.dto.VoiceCallRecordingResponse;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opt-in LiveKit Egress call recording ({@link LiveKitEgressService}'s {@code enabled} flag,
 * default OFF) for in-app calls. Not yet wired to the frontend's call lifecycle: {@code
 * CallPanel.tsx} would call start right after {@code room.connect()} resolves and stop right before
 * {@code room.disconnect()}.
 *
 * <p>Only the person the call room was issued to may record it: the room name must be one {@link
 * VoiceCallController#issueToken} mints for this agent ({@code voice|sight-<agentId>-<8 hex>}), and
 * {@link VoiceCallRoomRegistry} must show it was issued to this caller in this tenant. Anything
 * else is the same 404 as an unknown room, so room names cannot be probed.
 *
 * <p>Returns {@link ResponseEntity} rather than throwing for the expected outcomes: {@code
 * GlobalExceptionHandler}'s catch-all would turn a thrown {@code ResponseStatusException} into an
 * opaque 500.
 */
@ConditionalOnEditionFeature(EditionFeature.VOICE_REALTIME_SHARE)
@RestController
@RequestMapping("/api/voice/agents/{agentId}/calls/{roomName}/recording")
public class VoiceCallRecordingController {

    private static final Logger log = LoggerFactory.getLogger(VoiceCallRecordingController.class);

    private static final Pattern CALL_ROOM =
            Pattern.compile("^(?:voice|sight)-([0-9a-fA-F-]{36})-[0-9a-fA-F]{8}$");

    private static final List<VoiceCallRecordingStatus> IN_FLIGHT_STATUSES =
            List.of(
                    VoiceCallRecordingStatus.STARTING,
                    VoiceCallRecordingStatus.ACTIVE,
                    VoiceCallRecordingStatus.STOPPING);

    private final AgentService agentService;
    private final LiveKitEgressService egressService;
    private final VoiceCallRecordingRepository recordingRepository;
    private final VoiceCallRoomRegistry roomRegistry;

    public VoiceCallRecordingController(
            AgentService agentService,
            LiveKitEgressService egressService,
            VoiceCallRecordingRepository recordingRepository,
            VoiceCallRoomRegistry roomRegistry) {
        this.agentService = agentService;
        this.egressService = egressService;
        this.recordingRepository = recordingRepository;
        this.roomRegistry = roomRegistry;
    }

    /**
     * Starts recording the room, if this deployment has opted in. Idempotent: a second call for a
     * room that already has a recording in flight returns that recording rather than starting a
     * duplicate egress.
     */
    @PostMapping("/start")
    public ResponseEntity<?> startRecording(
            @PathVariable UUID agentId, @PathVariable String roomName, Principal principal) {
        agentService.get(agentId);
        if (!isCallersRoom(agentId, roomName, principal)) {
            return noSuchCall();
        }
        if (!egressService.isEnabled()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Call recording is not enabled on this deployment."));
        }

        var existing =
                recordingRepository.findFirstByRoomNameAndStatusInOrderByCreatedAtDesc(
                        roomName, IN_FLIGHT_STATUSES);
        if (existing.isPresent()) {
            return ResponseEntity.ok(toResponse(existing.get()));
        }

        LiveKitEgressService.EgressStartResult result;
        VoiceCallRecording recording;
        try {
            result = egressService.startRoomRecording(tenantId(), roomName);
            recording =
                    new VoiceCallRecording(
                            agentId, roomName, result.egressId(), result.objectKey());
            recording.markActive();
        } catch (LiveKitEgressException e) {
            log.warn(
                    "Failed to start LiveKit egress for agent {} room {}: {}",
                    agentId,
                    roomName,
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
                roomName,
                recording.getEgressId());
        return ResponseEntity.ok(toResponse(recording));
    }

    /** Stops whatever recording of this agent's room is in flight, if any. */
    @PostMapping("/stop")
    public ResponseEntity<?> stopRecording(
            @PathVariable UUID agentId, @PathVariable String roomName, Principal principal) {
        agentService.get(agentId);
        if (!isCallersRoom(agentId, roomName, principal)) {
            return noSuchCall();
        }
        var recordingOpt =
                recordingRepository
                        .findFirstByRoomNameAndStatusInOrderByCreatedAtDesc(
                                roomName, IN_FLIGHT_STATUSES)
                        .filter(r -> agentId.equals(r.getAgentId()));
        if (recordingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No in-progress recording for room " + roomName));
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
                    roomName,
                    e.getMessage());
            recording.markFailed();
        }
        recordingRepository.save(recording);
        log.info(
                "Stopped call recording agent={} room={} status={}",
                agentId,
                roomName,
                recording.getStatus());
        return ResponseEntity.ok(toResponse(recording));
    }

    /** The room is this agent's call room and was issued to this caller in this tenant. */
    private boolean isCallersRoom(UUID agentId, String roomName, Principal principal) {
        Matcher m = CALL_ROOM.matcher(roomName);
        if (!m.matches() || !m.group(1).equalsIgnoreCase(agentId.toString())) {
            return false;
        }
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            return false;
        }
        return roomRegistry.isIssuedTo(roomName, tenantId(), principal.getName());
    }

    /** The same tenant key {@link VoiceCallController#issueToken} registers the room under. */
    private static String tenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("default");
    }

    private static ResponseEntity<Map<String, String>> noSuchCall() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such call"));
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
