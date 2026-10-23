package com.agenticai.core.omnichannel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.telegram.dto.TelegramActivationRequest;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Telegram is given the raw webhook secret exactly once; the database only ever gets its SHA-256
 * digest, so a copy of the table cannot be replayed as the {@code X-Telegram-Bot-Api-Secret-Token}
 * header.
 */
class TelegramCredentialServiceSecretTest {

    @Test
    @SuppressWarnings("unchecked")
    void activateStoresOnlyTheDigestOfTheSecretItRegistersWithTelegram() {
        TelegramCredentialRepository repository = mock(TelegramCredentialRepository.class);
        EncryptionService encryption = mock(EncryptionService.class);
        TenantSchemaResolver schemaResolver = mock(TenantSchemaResolver.class);
        TelegramApiClient api = mock(TelegramApiClient.class);
        when(api.fetchBotInfo(anyString()))
                .thenReturn(new TelegramApiClient.BotInfo("42", "acme_bot"));
        when(encryption.encrypt(anyString())).thenReturn("enc-token");
        when(schemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_test");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(repository.save(any(TelegramCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        TelegramCredentialService service =
                new TelegramCredentialService(
                        repository,
                        encryption,
                        mock(TenantResolutionService.class),
                        schemaResolver,
                        api,
                        "https://core.example.com",
                        mock(ObjectProvider.class));

        service.activate(new TelegramActivationRequest("123:bot-token"));

        ArgumentCaptor<String> registered = ArgumentCaptor.forClass(String.class);
        verify(api).setWebhook(anyString(), anyString(), registered.capture());
        ArgumentCaptor<TelegramCredential> saved =
                ArgumentCaptor.forClass(TelegramCredential.class);
        verify(repository).save(saved.capture());

        String raw = registered.getValue();
        String stored = saved.getValue().getWebhookSecret();
        assertFalse(raw.equals(stored), "the raw secret must not be persisted");
        assertEquals(TelegramWebhookSecret.hash(raw), stored);
        assertEquals(64, stored.length());
        assertTrue(TelegramWebhookSecret.matches(raw, stored));
    }
}
