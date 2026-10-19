package com.agenticai.core.omnichannel.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.outbound.DirectTextDelivery;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Workflow Output sends to Teams: the conversation must be routable before anything is sent. */
class TeamsDirectTextDeliveryTest {

    private final TeamsCredentialService credentials = mock(TeamsCredentialService.class);
    private final TeamsApiClient api = mock(TeamsApiClient.class);
    private final TeamsDirectTextDelivery delivery = new TeamsDirectTextDelivery(credentials, api);

    @Test
    void anUnknownConversationIsAConfigurationErrorBeforeAnySend() {
        when(credentials.resolveActive())
                .thenReturn(new TeamsCredentialService.DecryptedCredential("app", "pw", "aad"));
        when(credentials.serviceUrlFor("conv-1")).thenReturn(Optional.empty());

        ChannelConfigurationException e =
                assertThrows(ChannelConfigurationException.class, () -> delivery.prepare("conv-1"));

        assertTrue(e.getMessage().contains("Unknown Teams conversation"), e.getMessage());
        verifyNoInteractions(api);
    }

    @Test
    void aKnownConversationSendsThroughTheBotFramework() {
        when(credentials.resolveActive())
                .thenReturn(new TeamsCredentialService.DecryptedCredential("app", "pw", "aad"));
        when(credentials.serviceUrlFor("conv-1")).thenReturn(Optional.of("https://smba.example/"));
        when(api.sendActivity("app", "pw", "aad", "https://smba.example/", "conv-1", "hello", null))
                .thenReturn("Sent.");

        DirectTextDelivery.Prepared prepared = delivery.prepare("conv-1");

        assertEquals("teams", delivery.channel());
        assertEquals("Sent.", prepared.send("hello"));
    }
}
