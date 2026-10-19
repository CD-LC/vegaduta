package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.AsyncInboundReply;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.tenancy.TenantResolutionService;
import org.junit.jupiter.api.Test;

/**
 * SEC-050: the Messenger/Instagram verify handshake compares constant-time and fails closed when
 * unset.
 */
class MetaSocialWebhookVerifyTokenTest {

    private static MetaSocialWebhookController controller(String configuredToken) {
        return new MetaSocialWebhookController(
                configuredToken,
                mock(MetaWhatsAppSignatureValidator.class),
                mock(MetaSocialCredentialService.class),
                mock(ChannelSessionService.class),
                mock(AgentChatService.class),
                mock(HumanApprovalGateService.class),
                mock(TenantResolutionService.class),
                mock(WorkflowEventPublisher.class),
                mock(WorkflowTriggerNodeService.class),
                mock(WorkflowTriggerPort.class),
                mock(InboundMessageDedupService.class),
                new AsyncInboundReply(Runnable::run));
    }

    @Test
    void correctTokenEchoesTheChallenge() {
        var response =
                controller("s3cret-verify").verify("subscribe", "s3cret-verify", "challenge-42");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("challenge-42", response.getBody());
    }

    @Test
    void wrongOrPrefixTokenIsRejected() {
        var c = controller("s3cret-verify");
        assertEquals(403, c.verify("subscribe", "s3cret-verifx", "c").getStatusCode().value());
        assertEquals(403, c.verify("subscribe", "s3cret", "c").getStatusCode().value());
        assertEquals(403, c.verify("subscribe", null, "c").getStatusCode().value());
        assertEquals(403, c.verify("unsubscribe", "s3cret-verify", "c").getStatusCode().value());
    }

    @Test
    void unsetConfiguredTokenFailsClosed() {
        assertEquals(403, controller("").verify("subscribe", "", "c").getStatusCode().value());
        assertEquals(
                403, controller("   ").verify("subscribe", "   ", "c").getStatusCode().value());
        assertEquals(
                403, controller(null).verify("subscribe", "anything", "c").getStatusCode().value());
    }
}
