package com.agenticai.core.omnichannel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The Telegram webhook's only authentication is the secret token Telegram echoes in {@code
 * X-Telegram-Bot-Api-Secret-Token}. The stored value is the secret's SHA-256 digest, so these pin
 * both halves: the raw secret is accepted, and nothing else is - not a wrong or missing or empty
 * header, not a credential with no stored digest, and not the stored digest itself replayed as the
 * header.
 */
class TelegramWebhookControllerAuthTest {

    private static final String BOT = "123456789";
    private static final String RAW_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final JsonNode UPDATE = update();

    private TelegramCredentialRepository repository;
    private TelegramUpdateService updateService;
    private TelegramWebhookController controller;

    private static JsonNode update() {
        try {
            return new ObjectMapper()
                    .readTree(
                            "{\"update_id\":1,\"message\":{\"chat\":{\"id\":5},\"text\":\"hi\"}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        TenantResolutionService tenants = mock(TenantResolutionService.class);
        repository = mock(TelegramCredentialRepository.class);
        EncryptionService encryption = mock(EncryptionService.class);
        updateService = mock(TelegramUpdateService.class);
        controller =
                new TelegramWebhookController(
                        tenants,
                        repository,
                        encryption,
                        updateService,
                        mock(InboundChannelExecutor.class));
        when(tenants.resolveTenantSchema(ExternalAccountProvider.TELEGRAM, BOT))
                .thenReturn(Optional.of("tenant_test"));
        when(encryption.decrypt("enc")).thenReturn("bot-token");
        when(updateService.acceptVerifiedUpdate(any(), any(), any())).thenReturn(true);
        storeSecretDigest(TelegramWebhookSecret.hash(RAW_SECRET));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void storeSecretDigest(String stored) {
        TelegramCredential credential = mock(TelegramCredential.class);
        when(credential.getWebhookSecret()).thenReturn(stored);
        when(credential.getEncryptedBotToken()).thenReturn("enc");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
    }

    private void assertForbidden(String header) {
        ResponseEntity<Void> response = controller.inbound(BOT, UPDATE, header);
        assertEquals(403, response.getStatusCode().value(), "header=" + header);
        verify(updateService, never()).acceptVerifiedUpdate(any(), any(), any());
    }

    @Test
    void theRawSecretIsAccepted() {
        ResponseEntity<Void> response = controller.inbound(BOT, UPDATE, RAW_SECRET);

        assertEquals(200, response.getStatusCode().value());
        verify(updateService).acceptVerifiedUpdate(BOT, UPDATE, "bot-token");
    }

    @Test
    void aWrongMissingOrEmptySecretIsRejected() {
        assertForbidden("not-the-secret");
        assertForbidden(null);
        assertForbidden("");
        assertForbidden(RAW_SECRET.substring(1));
        assertForbidden(RAW_SECRET + "0");
    }

    @Test
    void theStoredDigestReplayedAsTheHeaderIsRejected() {
        // A copy of the table must not be enough to authenticate as Telegram.
        assertForbidden(TelegramWebhookSecret.hash(RAW_SECRET));
    }

    @Test
    void aCredentialWithNoStoredSecretRejectsEveryCall() {
        storeSecretDigest(null);

        assertForbidden(RAW_SECRET);
        assertForbidden("");
    }

    @Test
    void aTenantWithNoCredentialRejectsTheCall() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        assertForbidden(RAW_SECRET);
    }
}
