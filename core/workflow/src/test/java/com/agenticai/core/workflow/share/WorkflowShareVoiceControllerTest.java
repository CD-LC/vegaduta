package com.agenticai.core.workflow.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.voice.VoiceCallController;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowStatus;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Pipeline-wiring coverage for {@link WorkflowShareVoiceController#turn} - every
 * collaborator ({@link WorkflowShareService}, {@link WorkflowService}, {@link
 * VoiceCallController}) is mocked directly (no real service construction, unlike
 * {@code AgentShareOwnerControllerTest}'s "real service, mock the leaves" style) since
 * there is no authorization/security logic in this controller to exercise against a
 * real collaborator - just "does step N call step N+1 with the right arguments, and
 * does each early-exit return the right status".
 */
class WorkflowShareVoiceControllerTest {

    private static final String SHARE_ID = "share-abc123";
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private WorkflowShareService shareService;
    private WorkflowService workflowService;
    private VoiceCallController voiceCallController;
    private WorkflowShareVoiceController controller;

    private void setUp() {
        shareService = mock(WorkflowShareService.class);
        workflowService = mock(WorkflowService.class);
        voiceCallController = mock(VoiceCallController.class);
        controller = new WorkflowShareVoiceController(shareService, workflowService, voiceCallController);
    }

    private static MockMultipartFile audioFile() {
        return new MockMultipartFile("audio", "clip.webm", "audio/webm", new byte[]{1, 2, 3, 4});
    }

    @Test
    void rateLimitedVisitorGets429AndNothingElseRuns() throws Exception {
        setUp();
        when(shareService.tryAcquireVoiceTurn(anyString())).thenReturn(false);

        ResponseEntity<?> response = controller.turn(SHARE_ID, audioFile(), new MockHttpServletRequest());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        verify(shareService, never()).resolveActiveShare(anyString());
        verify(workflowService, never()).run(any(), any(), any(), any(), any(), any());
    }

    @Test
    void expiredOrUnknownShareGets410() throws Exception {
        setUp();
        when(shareService.tryAcquireVoiceTurn(anyString())).thenReturn(true);
        when(shareService.resolveActiveShare(SHARE_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.turn(SHARE_ID, audioFile(), new MockHttpServletRequest());

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        verify(voiceCallController, never()).transcribeAudio(any(), any(), any(), any());
    }

    @Test
    void emptyOrUnusableAudioGets422() throws Exception {
        setUp();
        when(shareService.tryAcquireVoiceTurn(anyString())).thenReturn(true);
        when(shareService.resolveActiveShare(SHARE_ID)).thenReturn(Optional.of(resolvedShare()));
        when(voiceCallController.transcribeAudio(any(), isNull(), isNull(), isNull()))
            .thenThrow(new VoiceCallController.VoiceTurnAudioEmptyException("No speech was detected."));

        ResponseEntity<?> response = controller.turn(SHARE_ID, audioFile(), new MockHttpServletRequest());

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        verify(shareService, never()).recordViewer(any());
        verify(workflowService, never()).run(any(), any(), any(), any(), any(), any());
    }

    @Test
    void successfulTurnRunsTheWorkflowWithTheTranscriptAndSynthesizesTheResult() throws Exception {
        setUp();
        when(shareService.tryAcquireVoiceTurn(anyString())).thenReturn(true);
        WorkflowShareService.ResolvedShare resolvedShare = resolvedShare();
        when(shareService.resolveActiveShare(SHARE_ID)).thenReturn(Optional.of(resolvedShare));
        when(voiceCallController.transcribeAudio(any(), isNull(), isNull(), isNull()))
            .thenReturn(new VoiceCallController.TranscriptionOutcome("what's the weather", "sarvam"));

        WorkflowRunResponse runResponse = new WorkflowRunResponse(
            UUID.randomUUID(), WORKFLOW_ID, "Test Workflow", WorkflowRunStatus.COMPLETED,
            "system:workflow-share:" + SHARE_ID, null, List.of(), null, null, null, null);
        when(workflowService.run(eq(WORKFLOW_ID), isNull(), eq("what's the weather"),
            eq("workflow-share-voice:" + SHARE_ID), isNull(), eq(List.of()))).thenReturn(runResponse);

        when(voiceCallController.synthesizeSpeech(isNull(), anyString(), isNull()))
            .thenReturn(new VoiceCallController.SpeechSynthesisOutcome("QUJD", "elevenlabs", null));

        ResponseEntity<?> response = controller.turn(SHARE_ID, audioFile(), new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        WorkflowShareVoiceController.TurnResponse body = (WorkflowShareVoiceController.TurnResponse) response.getBody();
        assertEquals("QUJD", body.audioBase64());
        assertEquals("elevenlabs", body.method());
        assertEquals("COMPLETED", body.runStatus());

        verify(shareService).recordViewer(resolvedShare.share());
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(voiceCallController).synthesizeSpeech(isNull(), textCaptor.capture(), isNull());
        // deriveResultText falls back to this exact string when trace is empty and the
        // run didn't fail - see WorkflowShareController.deriveResultText's javadoc.
        assertEquals("The workflow finished with no output.", textCaptor.getValue());
    }

    private static WorkflowShareService.ResolvedShare resolvedShare() {
        WorkflowShare share = new WorkflowShare(WORKFLOW_ID, SHARE_ID);
        WorkflowDefinition workflow = new WorkflowDefinition(
            "Test Workflow", "desc", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE
        );
        setWorkflowId(workflow, WORKFLOW_ID);
        return new WorkflowShareService.ResolvedShare(share, workflow);
    }

    private static void setWorkflowId(WorkflowDefinition definition, UUID id) {
        try {
            java.lang.reflect.Field field = WorkflowDefinition.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(definition, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
