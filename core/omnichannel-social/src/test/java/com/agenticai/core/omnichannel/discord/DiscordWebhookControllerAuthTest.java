package com.agenticai.core.omnichannel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.discord.dto.DiscordInboundMessage;
import com.agenticai.core.omnichannel.session.AsyncInboundReply;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Discord inbound has no public signature: the only authentication is the shared secret the Gateway
 * bridge sends in {@code X-Internal-Secret}. A wrong, missing or empty header, and an operator who
 * never configured a secret, must all be a 403 that resolves no tenant and processes nothing.
 */
class DiscordWebhookControllerAuthTest {

    private static final String APPLICATION_ID = "app-12345678";
    private static final DiscordInboundMessage MESSAGE =
            new DiscordInboundMessage(APPLICATION_ID, "chan-1", "author-1", "hello", "1234567890");

    private final TenantResolutionService tenants = mock(TenantResolutionService.class);
    private final ChannelSessionService sessions = mock(ChannelSessionService.class);
    private final InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);

    private DiscordWebhookController controller(String sharedSecret) {
        when(tenants.resolveTenantSchema(ExternalAccountProvider.DISCORD, APPLICATION_ID))
                .thenReturn(Optional.of("tenant_test"));
        return new DiscordWebhookController(
                sharedSecret,
                tenants,
                sessions,
                mock(AgentChatService.class),
                mock(HumanApprovalGateService.class),
                mock(WorkflowEventPublisher.class),
                mock(WorkflowTriggerNodeService.class),
                mock(WorkflowTriggerPort.class),
                dedup,
                new AsyncInboundReply(Runnable::run));
    }

    private void assertRejectedBeforeAnyProcessing(ResponseEntity<Void> response) {
        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(tenants, sessions, dedup);
    }

    @Test
    void aWrongSecretIsRejected() {
        assertRejectedBeforeAnyProcessing(
                controller("right-secret").inbound(MESSAGE, "wrong-secret"));
    }

    @Test
    void aMissingSecretHeaderIsRejected() {
        assertRejectedBeforeAnyProcessing(controller("right-secret").inbound(MESSAGE, null));
    }

    @Test
    void anEmptySecretHeaderIsRejected() {
        assertRejectedBeforeAnyProcessing(controller("right-secret").inbound(MESSAGE, ""));
    }

    @Test
    void aSecretThatIsOnlyAPrefixOfTheRealOneIsRejected() {
        assertRejectedBeforeAnyProcessing(
                controller("right-secret").inbound(MESSAGE, "right-secre"));
    }

    @Test
    void whenNoSharedSecretIsConfiguredEveryCallIsRejectedEvenWithAnEmptyHeader() {
        DiscordWebhookController unconfigured = controller("");
        assertRejectedBeforeAnyProcessing(unconfigured.inbound(MESSAGE, ""));
        assertRejectedBeforeAnyProcessing(unconfigured.inbound(MESSAGE, "anything"));
        assertRejectedBeforeAnyProcessing(unconfigured.inbound(MESSAGE, null));
    }

    @Test
    void theCorrectSecretGetsPastTheGate() {
        DiscordWebhookController controller = controller("right-secret");

        ResponseEntity<Void> response = controller.inbound(MESSAGE, "right-secret");

        assertEquals(200, response.getStatusCode().value());
        verify(tenants).resolveTenantSchema(ExternalAccountProvider.DISCORD, APPLICATION_ID);
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }
}
