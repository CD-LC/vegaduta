package com.agenticai.core.omnichannel.discord;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * {@link DiscordMediaSender} itself has no HTTP logic - that's covered by {@link
 * DiscordApiClientMediaUploadTest} - so this only pins the orchestration: credential resolution
 * happens once via {@link DiscordCredentialService#resolveActive()} (the same path {@link
 * DiscordChannelAdapter} uses for text sends) and its Bot Token is the one actually forwarded to
 * {@link DiscordApiClient#sendMediaMessage}.
 */
class DiscordMediaSenderTest {

    private static final DiscordCredentialService.DecryptedCredential CREDENTIAL =
            new DiscordCredentialService.DecryptedCredential("app-1", "bot-token");

    private DiscordCredentialService credentialService;
    private DiscordApiClient discordApiClient;
    private DiscordMediaSender sender;

    private void setUp() {
        credentialService = mock(DiscordCredentialService.class);
        discordApiClient = mock(DiscordApiClient.class);
        sender = new DiscordMediaSender(credentialService, discordApiClient);
    }

    @Test
    void sendResolvesCredentialsThenDelegatesToDiscordApiClientWithTheResolvedToken()
            throws Exception {
        setUp();
        when(credentialService.resolveActive()).thenReturn(CREDENTIAL);
        byte[] bytes = {1, 2, 3};

        sender.send("c123", bytes, "image/png", "photo.png", "a caption");

        var inOrder = org.mockito.Mockito.inOrder(credentialService, discordApiClient);
        inOrder.verify(credentialService).resolveActive();
        inOrder.verify(discordApiClient)
                .sendMediaMessage(
                        "bot-token", "c123", bytes, "image/png", "photo.png", "a caption");
        verifyNoMoreInteractions(discordApiClient);
    }

    @Test
    void sendPropagatesAnUnconfiguredDiscordAsAnExceptionWithoutCallingTheApiClient() {
        setUp();
        when(credentialService.resolveActive()).thenThrow(new DiscordNotConfiguredException());

        assertThrows(
                DiscordNotConfiguredException.class,
                () -> sender.send("c123", new byte[] {1}, "image/png", "x.png", null));
        verifyNoMoreInteractions(discordApiClient);
    }

    @Test
    void sendPropagatesADeliveryFailureFromTheApiClient() {
        setUp();
        when(credentialService.resolveActive()).thenReturn(CREDENTIAL);
        when(discordApiClient.sendMediaMessage(
                        "bot-token", "c123", new byte[] {1}, "image/png", "x.png", null))
                .thenThrow(
                        new com.agenticai.core.omnichannel.ChannelDeliveryException(
                                "discord", "boom"));

        assertThrows(
                com.agenticai.core.omnichannel.ChannelDeliveryException.class,
                () -> sender.send("c123", new byte[] {1}, "image/png", "x.png", null));
    }
}
