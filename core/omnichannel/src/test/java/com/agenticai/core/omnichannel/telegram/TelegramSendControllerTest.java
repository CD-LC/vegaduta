package com.agenticai.core.omnichannel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link TelegramSendController}: manual sends go through the approval gate, never direct. */
class TelegramSendControllerTest {

    private HumanApprovalGateService gateService;
    private ChannelSessionService channelSessionService;
    private TelegramSendController controller;

    @BeforeEach
    void setUp() {
        gateService = mock(HumanApprovalGateService.class);
        channelSessionService = mock(ChannelSessionService.class);
        controller = new TelegramSendController(gateService, channelSessionService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sendEvaluatesThePayloadThroughTheApprovalGate() {
        UUID pending = UUID.randomUUID();
        when(gateService.evaluate(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ApprovalEvaluation(false, pending));

        TelegramSendController.SendResult result =
                controller.send(new TelegramSendController.SendRequest("chat-1", "hello"));

        verify(gateService)
                .evaluate(
                        eq(TelegramChannelAdapter.ACTION_TYPE),
                        eq("Send Telegram message to chat-1"),
                        contains("\"body\":\"hello\""),
                        any());
        assertEquals(false, result.approved());
        assertEquals(pending, result.pendingRequestId());
    }

    @Test
    void sendPassesTheCurrentSubjectAsRequestedBy() {
        TenantContext.set(TenantContext.of("tenant-1", "kc-sub-9", Set.of()));
        when(gateService.evaluate(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ApprovalEvaluation(true, null));

        TelegramSendController.SendResult result =
                controller.send(new TelegramSendController.SendRequest("chat-1", "hi"));

        verify(gateService)
                .evaluate(
                        eq("channel.telegram.send"),
                        anyString(),
                        contains("\"to\":\"chat-1\""),
                        eq("kc-sub-9"));
        assertTrue(result.approved());
        assertNull(result.pendingRequestId());
    }

    @Test
    void setDefaultAgentBindsTheTelegramChannel() {
        UUID agentId = UUID.randomUUID();
        controller.setDefaultAgent(new SetDefaultAgentRequest(agentId));
        verify(channelSessionService).setDefaultAgent(ChannelType.TELEGRAM, agentId);
    }
}
