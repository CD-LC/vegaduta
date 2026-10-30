package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import com.agenticai.core.omnichannel.twilio.dto.VoiceDtmfMappingResponse;
import com.agenticai.core.omnichannel.twilio.dto.VoiceDtmfMappingUpsertRequest;
import com.agenticai.core.omnichannel.twilio.dto.VoiceNumberAgentResponse;
import com.agenticai.core.omnichannel.twilio.dto.VoiceNumberAgentUpsertRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link VoiceChannelController}: the /api/channels/voice admin surface and its plan gate. */
class VoiceChannelControllerTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private ChannelSessionService channelSessionService;
    private VoiceDtmfMappingService voiceDtmfMappingService;
    private VoiceNumberAgentService voiceNumberAgentService;
    private TenantLimits tenantLimits;
    private VoiceChannelController controller;

    @BeforeEach
    void setUp() {
        channelSessionService = mock(ChannelSessionService.class);
        voiceDtmfMappingService = mock(VoiceDtmfMappingService.class);
        voiceNumberAgentService = mock(VoiceNumberAgentService.class);
        tenantLimits = mock(TenantLimits.class);
        controller =
                new VoiceChannelController(
                        channelSessionService,
                        voiceDtmfMappingService,
                        voiceNumberAgentService,
                        tenantLimits);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void setDefaultAgentDelegatesToTheSessionService() {
        controller.setDefaultAgent(new SetDefaultAgentRequest(AGENT_ID));
        verify(channelSessionService).setDefaultAgent(ChannelType.VOICE, AGENT_ID);
    }

    @Test
    void writesAreGatedOnTheExternalChannelsPlanFlag() {
        TenantContext.set(TenantContext.of("tenant-1", "admin", Set.of()));
        when(tenantLimits.externalChannelsAllowed("tenant-1")).thenReturn(false);

        assertThrows(
                TenantLimitExceededException.class,
                () -> controller.setDefaultAgent(new SetDefaultAgentRequest(AGENT_ID)));
        assertThrows(
                TenantLimitExceededException.class,
                () ->
                        controller.upsertNumber(
                                "+15551234567", new VoiceNumberAgentUpsertRequest(AGENT_ID, "x")));
        assertThrows(
                TenantLimitExceededException.class,
                () ->
                        controller.upsertDtmfMenuEntry(
                                "2", new VoiceDtmfMappingUpsertRequest(AGENT_ID, "x")));

        verifyNoInteractions(
                channelSessionService, voiceNumberAgentService, voiceDtmfMappingService);
    }

    @Test
    void writesPassTheGateWhenThePlanAllowsExternalChannels() {
        TenantContext.set(TenantContext.of("tenant-1", "admin", Set.of()));
        when(tenantLimits.externalChannelsAllowed("tenant-1")).thenReturn(true);
        VoiceNumberAgentUpsertRequest body = new VoiceNumberAgentUpsertRequest(AGENT_ID, "Sales");
        VoiceNumberAgentResponse mapped =
                new VoiceNumberAgentResponse("+15551234567", AGENT_ID, "Sales Bot", "Sales");
        when(voiceNumberAgentService.upsert("+15551234567", AGENT_ID, "Sales")).thenReturn(mapped);

        assertEquals(mapped, controller.upsertNumber("+15551234567", body));

        VoiceDtmfMappingUpsertRequest dtmf = new VoiceDtmfMappingUpsertRequest(AGENT_ID, "Support");
        VoiceDtmfMappingResponse dtmfMapped =
                new VoiceDtmfMappingResponse("2", AGENT_ID, "Support Bot", "Support");
        when(voiceDtmfMappingService.upsert("2", AGENT_ID, "Support")).thenReturn(dtmfMapped);

        assertEquals(dtmfMapped, controller.upsertDtmfMenuEntry("2", dtmf));
    }

    @Test
    void listNumbersSlicesTheInMemoryListWithPagingHeaders() {
        VoiceNumberAgentResponse row =
                new VoiceNumberAgentResponse("+15551234567", AGENT_ID, "Bot", "L");
        when(voiceNumberAgentService.list()).thenReturn(List.of(row, row, row));

        ResponseEntity<List<VoiceNumberAgentResponse>> all = controller.listNumbers(null, null);
        assertEquals(HttpStatus.OK, all.getStatusCode());
        assertEquals(3, all.getBody().size());
        assertEquals("3", all.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", all.getHeaders().getFirst("X-Has-More"));

        ResponseEntity<List<VoiceNumberAgentResponse>> page = controller.listNumbers(2, 2L);
        assertEquals(1, page.getBody().size());
        assertEquals("false", page.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void listDtmfMenuPagesTheSameWay() {
        VoiceDtmfMappingResponse row = new VoiceDtmfMappingResponse("2", AGENT_ID, "Bot", "L");
        when(voiceDtmfMappingService.list()).thenReturn(List.of(row, row));

        ResponseEntity<List<VoiceDtmfMappingResponse>> page = controller.listDtmfMenu(1, 0L);
        assertEquals(1, page.getBody().size());
        assertEquals("2", page.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", page.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void deletesStayUngated() {
        controller.deleteNumber("+15551234567");
        verify(voiceNumberAgentService).delete("+15551234567");

        controller.deleteDtmfMenuEntry("2");
        verify(voiceDtmfMappingService).delete("2");
    }
}
