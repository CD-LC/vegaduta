package com.agenticai.core.omnichannel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.telegram.dto.TelegramActivationRequest;
import com.agenticai.core.omnichannel.telegram.dto.TelegramCredentialResponse;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@link TelegramCredentialService}: BYOK activate/current/disconnect/resolveActive. */
class TelegramCredentialServiceTest {

    private TelegramCredentialRepository repository;
    private EncryptionService encryptionService;
    private TenantResolutionService tenantResolutionService;
    private TenantSchemaResolver tenantSchemaResolver;
    private TelegramApiClient apiClient;
    private ObjectProvider<TelegramPollingService> pollingProvider;
    private TelegramCredentialService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(TelegramCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        tenantSchemaResolver = mock(TenantSchemaResolver.class);
        apiClient = mock(TelegramApiClient.class);
        pollingProvider = mock(ObjectProvider.class);
        service =
                new TelegramCredentialService(
                        repository,
                        encryptionService,
                        tenantResolutionService,
                        tenantSchemaResolver,
                        apiClient,
                        "https://core.example.com",
                        pollingProvider);
    }

    @Test
    void activateValidatesEncryptsPersistsAndRegistersBothLinkAndWebhook() {
        TelegramActivationRequest request = new TelegramActivationRequest("bot-token");
        when(apiClient.fetchBotInfo("bot-token"))
                .thenReturn(new TelegramApiClient.BotInfo("bot-42", "mybot"));
        when(encryptionService.encrypt("bot-token")).thenReturn("enc-token");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(repository.save(any(TelegramCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");

        TelegramCredentialResponse response = service.activate(request);

        assertEquals("bot-42", response.botUserId());
        assertEquals("mybot", response.botUsername());
        assertEquals(TelegramCredentialStatus.ACTIVE, response.status());
        verify(tenantResolutionService)
                .link(ExternalAccountProvider.TELEGRAM, "bot-42", "tenant_a");
        verify(apiClient)
                .setWebhook(
                        eq("bot-token"),
                        eq("https://core.example.com/webhook/telegram/bot-42"),
                        anyString());
    }

    @Test
    void activateReactivatesTheExistingRowInsteadOfInserting() {
        TelegramCredential existing = new TelegramCredential("old", "oldbot", "enc-0", "hash-0");
        when(apiClient.fetchBotInfo("bot-token"))
                .thenReturn(new TelegramApiClient.BotInfo("bot-42", "mybot"));
        when(encryptionService.encrypt("bot-token")).thenReturn("enc-token");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));
        when(repository.save(any(TelegramCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");

        service.activate(new TelegramActivationRequest("bot-token"));

        assertEquals("bot-42", existing.getBotUserId());
        assertEquals("mybot", existing.getBotUsername());
        assertEquals("enc-token", existing.getEncryptedBotToken());
        verify(repository).save(existing);
    }

    @Test
    void activateWithoutAWebhookBaseUrlRegistersNoWebhook() {
        TelegramCredentialService pollingService =
                new TelegramCredentialService(
                        repository,
                        encryptionService,
                        tenantResolutionService,
                        tenantSchemaResolver,
                        apiClient,
                        "",
                        pollingProvider);
        when(apiClient.fetchBotInfo("bot-token"))
                .thenReturn(new TelegramApiClient.BotInfo("bot-42", "mybot"));
        when(encryptionService.encrypt("bot-token")).thenReturn("enc-token");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(repository.save(any(TelegramCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");

        pollingService.activate(new TelegramActivationRequest("bot-token"));

        verify(apiClient, never()).setWebhook(anyString(), anyString(), anyString());
        // The ensurePolling hop is deferred to an afterCommit synchronization, so it does not
        // run in a transaction-less unit test at all; an empty provider keeps this path inert.
    }

    @Test
    void currentIsEmptyWhenNoRowExistsAndMapsTheStoredRowOtherwise() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertTrue(service.current().isEmpty());

        TelegramCredential stored = new TelegramCredential("bot-1", "b", "enc", "h");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        TelegramCredentialResponse response = service.current().orElseThrow();
        assertEquals("bot-1", response.botUserId());
    }

    @Test
    void resolveActiveDecryptsTheTokenOrThrows() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertThrows(TelegramNotConfiguredException.class, () -> service.resolveActive());

        TelegramCredential stored = new TelegramCredential("bot-1", "b", "enc-tok", "h");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt("enc-tok")).thenReturn("raw-token");

        TelegramCredentialService.DecryptedCredential resolved = service.resolveActive();
        assertEquals("bot-1", resolved.botUserId());
        assertEquals("raw-token", resolved.botToken());

        stored.disconnect();
        assertThrows(TelegramNotConfiguredException.class, () -> service.resolveActive());
    }

    @Test
    void disconnectMarksTheRowUnlinksStopsPollingAndDeletesTheWebhook() {
        TelegramCredential stored = new TelegramCredential("bot-1", "b", "enc-tok", "h");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt("enc-tok")).thenReturn("raw-token");
        TelegramPollingService polling = mock(TelegramPollingService.class);
        doAnswer(
                        inv -> {
                            ((Consumer<TelegramPollingService>) inv.getArgument(0)).accept(polling);
                            return null;
                        })
                .when(pollingProvider)
                .ifAvailable(any());

        service.disconnect();

        assertEquals(TelegramCredentialStatus.DISCONNECTED, stored.getStatus());
        verify(repository).save(stored);
        verify(tenantResolutionService).unlink(ExternalAccountProvider.TELEGRAM, "bot-1");
        verify(polling).stopPolling("bot-1");
        verify(apiClient).deleteWebhook("raw-token");
    }

    @Test
    void disconnectStillCompletesWhenDeleteWebhookFails() {
        TelegramCredential stored = new TelegramCredential("bot-1", "b", "enc-tok", "h");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt("enc-tok")).thenReturn("raw-token");
        doThrow(new RuntimeException("telegram unreachable"))
                .when(apiClient)
                .deleteWebhook("raw-token");

        service.disconnect();

        assertEquals(TelegramCredentialStatus.DISCONNECTED, stored.getStatus());
        verify(tenantResolutionService).unlink(ExternalAccountProvider.TELEGRAM, "bot-1");
    }

    @Test
    void disconnectIsANoOpWhenNothingWasEverConnected() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        service.disconnect();

        verifyNoInteractions(tenantResolutionService, apiClient);
    }
}
