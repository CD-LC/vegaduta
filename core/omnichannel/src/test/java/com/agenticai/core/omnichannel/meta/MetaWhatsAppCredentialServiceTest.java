package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.meta.dto.MetaActivationRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaCredentialResponse;
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

/** {@link MetaWhatsAppCredentialService}: BYOK activate/current/isConfigured/resolveActive. */
class MetaWhatsAppCredentialServiceTest {

    private MetaWhatsAppCredentialRepository repository;
    private EncryptionService encryptionService;
    private TenantResolutionService tenantResolutionService;
    private TenantSchemaResolver tenantSchemaResolver;
    private MetaGraphApiClient graphApiClient;
    private TenantLimits tenantLimits;
    private MetaWhatsAppCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(MetaWhatsAppCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        tenantSchemaResolver = mock(TenantSchemaResolver.class);
        graphApiClient = mock(MetaGraphApiClient.class);
        tenantLimits = mock(TenantLimits.class);
        service =
                new MetaWhatsAppCredentialService(
                        repository,
                        encryptionService,
                        tenantResolutionService,
                        tenantSchemaResolver,
                        graphApiClient,
                        tenantLimits);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MetaActivationRequest request() {
        return new MetaActivationRequest("pn-1", "waba-1", "access-token", "app-secret");
    }

    @Test
    void activateValidatesEncryptsPersistsAndLinksThePhoneNumberId() {
        when(graphApiClient.fetchPhoneNumberInfo("pn-1", "access-token"))
                .thenReturn(new MetaGraphApiClient.PhoneNumberInfo("+15551234567", "Acme"));
        when(encryptionService.encrypt("access-token")).thenReturn("enc-tok");
        when(encryptionService.encrypt("app-secret")).thenReturn("enc-sec");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(repository.save(any(MetaWhatsAppCredential.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");

        MetaCredentialResponse response = service.activate(request());

        assertEquals("pn-1", response.phoneNumberId());
        assertEquals("waba-1", response.wabaId());
        assertEquals("+15551234567", response.displayPhoneNumber());
        assertEquals(MetaWhatsAppCredentialStatus.ACTIVE, response.status());
        verify(tenantResolutionService)
                .link(ExternalAccountProvider.META_WHATSAPP, "pn-1", "tenant_a");
    }

    @Test
    void activateReactivatesTheExistingRowInsteadOfInserting() {
        MetaWhatsAppCredential existing =
                new MetaWhatsAppCredential("old-pn", "old-waba", "e", "s", "+1");
        when(graphApiClient.fetchPhoneNumberInfo("pn-1", "access-token"))
                .thenReturn(new MetaGraphApiClient.PhoneNumberInfo("+15551234567", "Acme"));
        when(encryptionService.encrypt("access-token")).thenReturn("enc-tok");
        when(encryptionService.encrypt("app-secret")).thenReturn("enc-sec");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));
        when(repository.save(any(MetaWhatsAppCredential.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");

        service.activate(request());

        assertEquals("pn-1", existing.getPhoneNumberId());
        assertEquals("waba-1", existing.getWabaId());
        assertEquals("enc-tok", existing.getEncryptedAccessToken());
        assertEquals("enc-sec", existing.getEncryptedAppSecret());
        verify(repository).save(existing);
    }

    @Test
    void activateThrowsWhenThePlanBlocksWhatsApp() {
        TenantContext.set(TenantContext.of("tenant-1", "admin", Set.of()));
        when(tenantLimits.whatsappAllowed("tenant-1")).thenReturn(false);

        assertThrows(TenantLimitExceededException.class, () -> service.activate(request()));
        verifyNoInteractions(graphApiClient, repository);
    }

    @Test
    void currentIsEmptyWhenNoRowExistsAndMapsTheStoredRowOtherwise() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertTrue(service.current().isEmpty());

        MetaWhatsAppCredential stored =
                new MetaWhatsAppCredential("pn-1", "waba-1", "e", "s", "+1555");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        assertEquals("pn-1", service.current().orElseThrow().phoneNumberId());
    }

    @Test
    void isConfiguredTracksTheStoredRowStatus() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertFalse(service.isConfigured());

        MetaWhatsAppCredential stored = new MetaWhatsAppCredential("p", "w", "e", "s", null);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        assertTrue(service.isConfigured());

        stored.disconnect();
        assertFalse(service.isConfigured());
    }

    @Test
    void resolveActiveDecryptsBothSecretsOrThrows() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertThrows(MetaWhatsAppNotConfiguredException.class, () -> service.resolveActive());

        MetaWhatsAppCredential stored =
                new MetaWhatsAppCredential("pn-1", "waba-1", "enc-tok", "enc-sec", null);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt("enc-tok")).thenReturn("raw-token");
        when(encryptionService.decrypt("enc-sec")).thenReturn("raw-secret");

        MetaWhatsAppCredentialService.DecryptedCredential resolved = service.resolveActive();
        assertEquals("pn-1", resolved.phoneNumberId());
        assertEquals("raw-token", resolved.accessToken());
        assertEquals("raw-secret", resolved.appSecret());
        assertEquals("waba-1", resolved.wabaId());

        stored.disconnect();
        assertThrows(MetaWhatsAppNotConfiguredException.class, () -> service.resolveActive());
    }

    @Test
    void disconnectMarksTheRowAndDropsTheTenantLink() {
        MetaWhatsAppCredential stored = new MetaWhatsAppCredential("pn-1", "w", "e", "s", null);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));

        service.disconnect();

        assertEquals(MetaWhatsAppCredentialStatus.DISCONNECTED, stored.getStatus());
        verify(repository).save(stored);
        verify(tenantResolutionService).unlink(ExternalAccountProvider.META_WHATSAPP, "pn-1");
    }

    @Test
    void disconnectIsANoOpWhenNothingWasEverConnected() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        service.disconnect();

        verify(repository, never()).save(any());
        verifyNoInteractions(tenantResolutionService);
    }
}
