package com.agenticai.core.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.security.TenantContext;
import java.security.Principal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Only the person a call room was issued to can record that call. */
class VoiceCallRecordingControllerTest {

    private final UUID agentId = UUID.randomUUID();
    private final String room = "voice-" + agentId + "-1a2b3c4d";
    private final Principal alice = () -> "alice";
    private LiveKitEgressService egress;
    private VoiceCallRecordingRepository recordings;
    private VoiceCallRoomRegistry rooms;
    private VoiceCallRecordingController controller;

    @BeforeEach
    void setUp() {
        egress = mock(LiveKitEgressService.class);
        recordings = mock(VoiceCallRecordingRepository.class);
        rooms = mock(VoiceCallRoomRegistry.class);
        controller =
                new VoiceCallRecordingController(
                        mock(AgentService.class), egress, recordings, rooms);
        when(egress.isEnabled()).thenReturn(true);
        when(recordings.findFirstByRoomNameAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        TenantContext.set(TenantContext.of("acme", "alice", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theCallerRecordsTheirOwnCall() {
        when(rooms.isIssuedTo(room, "acme", "alice")).thenReturn(true);
        when(egress.startRoomRecording("acme", room))
                .thenReturn(new LiveKitEgressService.EgressStartResult("eg-1", "key"));

        assertThat(controller.startRecording(agentId, room, alice).getStatusCode().value())
                .isEqualTo(200);
        verify(egress).startRoomRecording("acme", room);
    }

    @Test
    void someoneElsesCallRoomIsANotFound() {
        when(rooms.isIssuedTo(room, "acme", "alice")).thenReturn(false);

        assertThat(controller.startRecording(agentId, room, alice).getStatusCode().value())
                .isEqualTo(404);
        assertThat(controller.stopRecording(agentId, room, alice).getStatusCode().value())
                .isEqualTo(404);
        verify(egress, never()).startRoomRecording(anyString(), anyString());
        verify(egress, never()).stopRecording(anyString());
    }

    @Test
    void aRoomOfAnotherAgentIsRejectedBeforeTheRegistryIsAsked() {
        String otherAgentsRoom = "voice-" + UUID.randomUUID() + "-1a2b3c4d";

        assertThat(
                        controller
                                .startRecording(agentId, otherAgentsRoom, alice)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        assertThat(
                        controller
                                .startRecording(agentId, "share-voice-x", alice)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        verify(rooms, never()).isIssuedTo(any(), any(), any());
    }

    @Test
    void stopIgnoresARecordingOfTheSameRoomNameUnderAnotherAgent() {
        when(rooms.isIssuedTo(room, "acme", "alice")).thenReturn(true);
        VoiceCallRecording foreign = new VoiceCallRecording(UUID.randomUUID(), room, "eg-9", "k");
        when(recordings.findFirstByRoomNameAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.of(foreign));

        assertThat(controller.stopRecording(agentId, room, alice).getStatusCode().value())
                .isEqualTo(404);
        verify(egress, never()).stopRecording(anyString());
    }
}
