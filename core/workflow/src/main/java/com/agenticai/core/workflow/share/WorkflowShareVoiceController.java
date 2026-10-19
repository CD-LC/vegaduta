package com.agenticai.core.workflow.share;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.voice.VoiceCallController;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /api/share/workflows/{shareId}/turn} - push-to-talk voice counterpart of
 * {@link WorkflowShareController#run}, same public/anonymous posture as every other
 * endpoint in that class's own javadoc: possession of the unguessable share id is the
 * whole credential, no auth of any kind.
 *
 * <p><b>Placement.</b> This lives in core/workflow, NOT core/voice, per the
 * dependency-direction check this class's build task required: {@code core/voice}'s
 * own {@code pom.xml} declares no dependency on {@code core/workflow} anywhere, so
 * {@code core/workflow} declaring a (new) dependency on {@code core/voice} - added
 * alongside this file - is cycle-free. This is the OPPOSITE direction from {@code
 * com.agenticai.core.voice.AgentShareVoiceController}, which lives in {@code
 * core/voice} instead - that one exists because {@code core/voice} already depended on
 * {@code core/agents} (whose {@code share} package it needed), not because of any
 * general rule about which module a share-voice controller belongs in.
 *
 * <p><b>Pipeline</b> (mirrors {@link WorkflowShareController#run} for steps 1-2 and
 * 4-5, replacing its plain-text request/response with speech):
 * <ol>
 *   <li>Per-visitor rate limit - {@link WorkflowShareService#tryAcquireVoiceTurn}, same
 *       {@code "workflow-share:<shareId>:<ipHash>"} key shape {@link
 *       WorkflowShareService#tryAcquireRun}'s sibling voice-turn bucket uses.</li>
 *   <li>{@link WorkflowShareService#resolveActiveShare} - 410 for an unknown/expired
 *       share, same as every other endpoint in {@link WorkflowShareController}.</li>
 *   <li>Transcribe the uploaded utterance via {@link VoiceCallController#transcribeAudio}
 *       - the exact same STT fallback chain {@code VoiceCallController#turn} runs,
 *       minus the agent-specific primary tier (a workflow share has no agent, so no
 *       configured voice-model provider to try first - {@code null}/{@code null} skips
 *       straight to sidecar Whisper, then Sarvam). A transcription failure (including a
 *       blank result) throws {@link VoiceCallController.VoiceTurnAudioEmptyException},
 *       caught here and mapped to 422 rather than the 400 {@code GlobalExceptionHandler}
 *       would otherwise give it - this endpoint's caller is a bespoke share widget, not
 *       the authenticated voice-call UI that exception's message was written for.</li>
 *   <li>{@link WorkflowShareService#recordViewer} then the workflow's normal run path -
 *       the exact {@link WorkflowService#run} call {@link WorkflowShareController#run}
 *       makes, transcript standing in for the typed message.</li>
 *   <li>{@link WorkflowShareController#deriveResultText} - reused, not duplicated, per
 *       that method's own javadoc inviting exactly this.</li>
 *   <li>Synthesize the result text back to speech via {@link
 *       VoiceCallController#synthesizeSpeech} with a {@code null} voice id - a workflow
 *       has no configured/cloned voice, so this always resolves to whatever platform
 *       default {@link VoiceCallController#speak} already falls back to for that case
 *       (see that method's own javadoc).</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/share/workflows")
public class WorkflowShareVoiceController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowShareVoiceController.class);

    private final WorkflowShareService shareService;
    private final WorkflowService workflowService;
    private final VoiceCallController voiceCallController;

    public WorkflowShareVoiceController(
        WorkflowShareService shareService, WorkflowService workflowService, VoiceCallController voiceCallController
    ) {
        this.shareService = shareService;
        this.workflowService = workflowService;
        this.voiceCallController = voiceCallController;
    }

    /** {@code audioBase64}/{@code method} are {@link VoiceCallController.SpeechSynthesisOutcome}'s own pair - see its javadoc for what {@code method} can be. */
    public record TurnResponse(String audioBase64, String method, String resultText, String runStatus) {
    }

    /**
     * A viewer's push-to-talk turn - see this class's javadoc for the full pipeline.
     * Returns a plain {@link ResponseEntity} for the rate-limit/expired/empty-audio
     * cases (same reasoning {@link
     * com.agenticai.core.voice.AgentShareVoiceController#turn}'s javadoc documents for
     * its own equivalent endpoint: a thrown exception with no specific {@code
     * GlobalExceptionHandler} mapping becomes an opaque 500).
     */
    @PostMapping(value = "/{shareId}/turn", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> turn(
        @PathVariable String shareId, @RequestParam("audio") MultipartFile audio, HttpServletRequest servletRequest
    ) throws IOException {
        String clientIp = servletRequest.getRemoteAddr();
        String rateLimitKey = "workflow-share:" + shareId + ":" + WorkflowShareService.ipHash(clientIp);
        if (!shareService.tryAcquireVoiceTurn(rateLimitKey)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate_limited"));
        }
        Optional<WorkflowShareService.ResolvedShare> resolved = shareService.resolveActiveShare(shareId);
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            VoiceCallController.TranscriptionOutcome transcription;
            try {
                // No agent, so no preferred provider/model - the fallback
                // chain starts at sidecar Whisper, then Sarvam. See this
                // method's javadoc for why null/null is correct here.
                transcription = voiceCallController.transcribeAudio(audio, null, null, null);
            } catch (VoiceCallController.VoiceTurnAudioEmptyException e) {
                log.info("Workflow share voice turn for share {} produced no usable speech: {}", shareId, e.getMessage());
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "empty_audio"));
            }
            String transcript = transcription.text();

            WorkflowShare share = resolved.get().share();
            shareService.recordViewer(share);
            WorkflowRunResponse runResponse = workflowService.run(
                resolved.get().workflow().getId(), null, transcript, "workflow-share-voice:" + share.getShareId(), null, List.of()
            );
            String resultText = WorkflowShareController.deriveResultText(runResponse);

            // No configured/cloned voice for a workflow - null resolves to
            // the tenant's own cloned ElevenLabs voice if one exists, else
            // the public "Rachel" default. See synthesizeSpeech's javadoc.
            VoiceCallController.SpeechSynthesisOutcome speech = voiceCallController.synthesizeSpeech(null, resultText, null);

            return ResponseEntity.ok(new TurnResponse(
                speech.audioBase64(), speech.method(), resultText, runResponse.status().name()
            ));
        } catch (RuntimeException e) {
            log.warn("Workflow share voice turn failed for share {}", shareId, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "upstream_failed"));
        } finally {
            TenantContext.clear();
        }
    }
}
