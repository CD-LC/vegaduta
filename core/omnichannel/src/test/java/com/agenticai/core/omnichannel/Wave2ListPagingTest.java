package com.agenticai.core.omnichannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.omnichannel.contact.ChannelContactController;
import com.agenticai.core.omnichannel.contact.ChannelContactService;
import com.agenticai.core.omnichannel.contact.ChannelRoutingController;
import com.agenticai.core.omnichannel.email.EmailMessageController;
import com.agenticai.core.omnichannel.email.EmailMessageRepository;
import com.agenticai.core.omnichannel.handoff.HandoffEscalationService;
import com.agenticai.core.omnichannel.handoff.OperatorInboxController;
import com.agenticai.core.omnichannel.handoff.OperatorInboxService;
import com.agenticai.core.omnichannel.session.ChannelPairingController;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.twilio.VoiceChannelController;
import com.agenticai.core.omnichannel.twilio.VoiceDtmfMappingService;
import com.agenticai.core.omnichannel.twilio.VoiceNumberAgentService;
import com.agenticai.core.security.TenantLimits;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Channel lists: additive limit/offset + X-Total-Count / X-Has-More (wave 2 A). */
class Wave2ListPagingTest {

    private static void assertPaged(MockMvc mvc, String path) throws Exception {
        mvc.perform(get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
        mvc.perform(get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON).param("limit", "5").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON).param("limit", "5").param("offset", "999999999999"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void contactGroups() throws Exception {
        var c =
                new ChannelContactController(
                        mock(ChannelContactService.class),
                        mock(ChannelSessionRepository.class),
                        mock(AgentRepository.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/channels/contact-groups");
    }

    @Test
    void pairingPending() throws Exception {
        var c = new ChannelPairingController(mock(ChannelSessionService.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/channels/pairing/pending");
    }

    @Test
    void voiceNumbersAndDtmfMenu() throws Exception {
        var c =
                new VoiceChannelController(
                        mock(ChannelSessionService.class),
                        mock(VoiceDtmfMappingService.class),
                        mock(VoiceNumberAgentService.class),
                        mock(TenantLimits.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(c).build();
        assertPaged(mvc, "/api/channels/voice/numbers");
        assertPaged(mvc, "/api/channels/voice/dtmf-menu");
    }

    @Test
    void emailMessages() throws Exception {
        var c = new EmailMessageController(mock(EmailMessageRepository.class));
        var mvc = MockMvcBuilders.standaloneSetup(c).build();
        mvc.perform(get("/api/email/messages").accept(org.springframework.http.MediaType.APPLICATION_JSON).param("runId", "r1"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"));
        mvc.perform(get("/api/email/messages").accept(org.springframework.http.MediaType.APPLICATION_JSON).param("runId", "r1").param("limit", "2").param("offset", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void operatorInbox() throws Exception {
        var c =
                new OperatorInboxController(
                        mock(OperatorInboxService.class), mock(HandoffEscalationService.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/handoff/inbox");
    }

    @Test
    void routingWindowsTheFixedChannelList() throws Exception {
        var c = new ChannelRoutingController(mock(ChannelSessionService.class), mock(AgentRepository.class));
        var mvc = MockMvcBuilders.standaloneSetup(c).build();
        int n = ChannelType.values().length;
        mvc.perform(get("/api/channels/routing"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", String.valueOf(n)))
                .andExpect(header().string("X-Has-More", "false"));
        var page = c.list(2, 1L);
        assertEquals(2, page.getBody().size());
        assertEquals(String.valueOf(n), page.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", page.getHeaders().getFirst("X-Has-More"));
    }
}
