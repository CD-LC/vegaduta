package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.twilio.dto.TwilioActivationRequest;
import com.agenticai.core.omnichannel.twilio.dto.TwilioCredentialResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TwilioCredentialService}: everything except {@code activate()}'s live Twilio validation
 * call (which needs the real API) - the plan gate and the api-key pairing rule are checked before
 * that call and are covered here.
 */
class TwilioCredentialServiceTest {

    private TwilioCredentialRepository repository;
    private EncryptionService encryptionService;
    private TenantResolutionService tenantResolutionService;
    private TenantSchemaResolver tenantSchemaResolver;
    private TenantLimits tenantLimits;
    private TwilioCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(TwilioCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        tenantSchemaResolver = mock(TenantSchemaResolver.class);
        tenantLimits = mock(TenantLimits.class);
        service =
                new TwilioCredentialService(
                        repository,
                        encryptionService,
                        tenantResolutionService,
                        tenantSchemaResolver,
                        tenantLimits);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void currentIsEmptyWhenNoRowExists() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertTrue(service.current().isEmpty());
    }

    @Test
    void currentMapsTheStoredRowIntoTheResponse() {
        TwilioCredential stored =
                new TwilioCredential("AC123", "enc", "SK1", "enc-k", "+15551234567");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));

        TwilioCredentialResponse response = service.current().orElseThrow();
        assertEquals("AC123", response.accountSid());
        assertEquals("SK1", response.apiKeySid());
        assertTrue(response.usesApiKey());
        assertEquals("+15551234567", response.whatsAppFromNumber());
        assertEquals(TwilioCredentialStatus.ACTIVE, response.status());
    }

    @Test
    void isActiveTracksTheStoredRowStatus() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertFalse(service.isActive());

        TwilioCredential active = new TwilioCredential("AC1", "e", null, null, "+1555");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(active));
        assertTrue(service.isActive());

        active.disconnect();
        assertFalse(service.isActive());
    }

    @Test
    void resolveActiveDecryptsTheAuthToken() {
        TwilioCredential stored = new TwilioCredential("AC1", "enc-tok", null, null, "+1555");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt("enc-tok")).thenReturn("raw-token");

        TwilioCredentialService.DecryptedCredential resolved = service.resolveActive();
        assertEquals("AC1", resolved.accountSid());
        assertEquals("raw-token", resolved.authToken());
        assertNull(resolved.apiKeySid());
        assertNull(resolved.apiKeySecret());
        assertEquals("+1555", resolved.whatsAppFromNumber());
    }

    @Test
    void resolveActiveDecryptsTheApiKeyPairWhenConfigured() {
        TwilioCredential stored =
                new TwilioCredential("AC1", "enc-tok", "SK9", "enc-secret", "+1555");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt("enc-tok")).thenReturn("raw-token");
        when(encryptionService.decrypt("enc-secret")).thenReturn("raw-secret");

        TwilioCredentialService.DecryptedCredential resolved = service.resolveActive();
        assertEquals("SK9", resolved.apiKeySid());
        assertEquals("raw-secret", resolved.apiKeySecret());
    }

    @Test
    void resolveActiveThrowsWhenNothingIsConfiguredOrActive() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertThrows(TwilioNotConfiguredException.class, () -> service.resolveActive());

        TwilioCredential disconnected = new TwilioCredential("AC1", "e", null, null, "+1555");
        disconnected.disconnect();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(disconnected));
        assertThrows(TwilioNotConfiguredException.class, () -> service.resolveActive());
    }

    @Test
    void disconnectMarksTheRowAndDropsTheTenantLink() {
        TwilioCredential stored = new TwilioCredential("AC1", "e", null, null, "+1555");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));

        service.disconnect();

        assertEquals(TwilioCredentialStatus.DISCONNECTED, stored.getStatus());
        verify(repository).save(stored);
        verify(tenantResolutionService).unlink(ExternalAccountProvider.TWILIO, "AC1");
    }

    @Test
    void disconnectIsANoOpWhenNothingWasEverConnected() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        service.disconnect();

        verify(repository, never()).save(any());
        verifyNoInteractions(tenantResolutionService);
    }

    @Test
    void activateThrowsWhenThePlanBlocksExternalChannels() {
        TenantContext.set(TenantContext.of("tenant-1", "admin", Set.of()));
        when(tenantLimits.externalChannelsAllowed("tenant-1")).thenReturn(false);

        assertThrows(
                TenantLimitExceededException.class,
                () ->
                        service.activate(
                                new TwilioActivationRequest("AC1", "token", null, null, "+1555")));

        verifyNoInteractions(repository);
    }

    @Test
    void activateRejectsAnApiKeySidWithoutItsSecretBeforeAnyRemoteCall() {
        TwilioActivationRequest request =
                new TwilioActivationRequest("AC1", "token", "SK1", null, "+1555");

        ChannelConfigurationException e =
                assertThrows(ChannelConfigurationException.class, () -> service.activate(request));
        assertTrue(e.getMessage().contains("apiKeySecret"));
        assertEquals("whatsapp", e.getChannelName());
        verifyNoInteractions(repository, encryptionService);
    }
}
