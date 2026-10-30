package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.meta.dto.MetaSocialActivationRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaSocialCredentialResponse;
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

/** {@link MetaSocialCredentialService}: the Page credential behind Messenger and Instagram. */
class MetaSocialCredentialServiceTest {

    private MetaSocialCredentialRepository repository;
    private EncryptionService encryptionService;
    private TenantResolutionService tenantResolutionService;
    private TenantSchemaResolver tenantSchemaResolver;
    private MetaGraphApiClient graphApiClient;
    private TenantLimits tenantLimits;
    private MetaSocialCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(MetaSocialCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        tenantSchemaResolver = mock(TenantSchemaResolver.class);
        graphApiClient = mock(MetaGraphApiClient.class);
        tenantLimits = mock(TenantLimits.class);
        service =
                new MetaSocialCredentialService(
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

    @Test
    void activateWithoutInstagramLinksOnlyTheMessengerSurface() {
        MetaSocialActivationRequest request =
                new MetaSocialActivationRequest("page-1", "page-token", "app-secret", null);
        when(graphApiClient.fetchPageInfo("page-1", "page-token"))
                .thenReturn(new MetaGraphApiClient.PageInfo("Acme Page"));
        when(encryptionService.encrypt("page-token")).thenReturn("enc-tok");
        when(encryptionService.encrypt("app-secret")).thenReturn("enc-sec");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(repository.save(any(MetaSocialCredential.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");

        MetaSocialCredentialResponse response = service.activate(request);

        assertEquals("page-1", response.pageId());
        assertEquals("Acme Page", response.pageName());
        assertFalse(response.instagramConnected());
        assertNull(response.instagramUsername());
        assertEquals(MetaSocialCredentialStatus.ACTIVE, response.status());
        verify(tenantResolutionService)
                .link(ExternalAccountProvider.META_MESSENGER, "page-1", "tenant_a");
        verify(graphApiClient, never()).fetchInstagramUsername(anyString(), anyString());
    }

    @Test
    void activateWithInstagramLooksUpTheUsernameAndLinksBothSurfaces() {
        MetaSocialActivationRequest request =
                new MetaSocialActivationRequest("page-1", "page-token", "app-secret", "ig-9");
        when(graphApiClient.fetchPageInfo("page-1", "page-token"))
                .thenReturn(new MetaGraphApiClient.PageInfo("Acme Page"));
        when(graphApiClient.fetchInstagramUsername("ig-9", "page-token")).thenReturn("@acme");
        when(encryptionService.encrypt("page-token")).thenReturn("enc-tok");
        when(encryptionService.encrypt("app-secret")).thenReturn("enc-sec");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(repository.save(any(MetaSocialCredential.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(tenantSchemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");

        MetaSocialCredentialResponse response = service.activate(request);

        assertTrue(response.instagramConnected());
        assertEquals("@acme", response.instagramUsername());
        verify(tenantResolutionService)
                .link(ExternalAccountProvider.META_MESSENGER, "page-1", "tenant_a");
        verify(tenantResolutionService)
                .link(ExternalAccountProvider.META_INSTAGRAM, "ig-9", "tenant_a");
    }

    @Test
    void activateThrowsWhenThePlanBlocksExternalChannels() {
        TenantContext.set(TenantContext.of("tenant-1", "admin", Set.of()));
        when(tenantLimits.externalChannelsAllowed("tenant-1")).thenReturn(false);

        assertThrows(
                TenantLimitExceededException.class,
                () ->
                        service.activate(
                                new MetaSocialActivationRequest(
                                        "page-1", "page-token", "app-secret", null)));
        verifyNoInteractions(graphApiClient, repository);
    }

    @Test
    void currentIsEmptyWhenNoRowExistsAndMapsTheStoredRowOtherwise() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertTrue(service.current().isEmpty());

        MetaSocialCredential stored =
                new MetaSocialCredential("page-1", "Page", "e", "s", "ig-1", "@acme");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        MetaSocialCredentialResponse response = service.current().orElseThrow();
        assertEquals("page-1", response.pageId());
        assertTrue(response.instagramConnected());
    }

    @Test
    void resolveActiveDecryptsBothSecretsOrThrows() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertThrows(MetaSocialNotConfiguredException.class, () -> service.resolveActive());

        MetaSocialCredential stored =
                new MetaSocialCredential("page-1", "Page", "enc-tok", "enc-sec", "ig-1", "@acme");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt("enc-tok")).thenReturn("raw-token");
        when(encryptionService.decrypt("enc-sec")).thenReturn("raw-secret");

        MetaSocialCredentialService.DecryptedCredential resolved = service.resolveActive();
        assertEquals("page-1", resolved.pageId());
        assertEquals("raw-token", resolved.pageAccessToken());
        assertEquals("raw-secret", resolved.appSecret());
        assertEquals("ig-1", resolved.instagramBusinessAccountId());
        assertTrue(resolved.hasInstagram());

        stored.disconnect();
        assertThrows(MetaSocialNotConfiguredException.class, () -> service.resolveActive());
    }

    @Test
    void resolveActiveReportsNoInstagramForAMessengerOnlyRow() {
        MetaSocialCredential stored =
                new MetaSocialCredential("page-1", "Page", "enc-tok", "enc-sec", null, null);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));
        when(encryptionService.decrypt(anyString())).thenReturn("raw");

        MetaSocialCredentialService.DecryptedCredential resolved = service.resolveActive();
        assertNull(resolved.instagramBusinessAccountId());
        assertFalse(resolved.hasInstagram());
    }

    @Test
    void disconnectUnlinksBothSurfacesWhenInstagramWasConnected() {
        MetaSocialCredential stored =
                new MetaSocialCredential("page-1", "Page", "e", "s", "ig-1", "@acme");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));

        service.disconnect();

        assertEquals(MetaSocialCredentialStatus.DISCONNECTED, stored.getStatus());
        verify(repository).save(stored);
        verify(tenantResolutionService).unlink(ExternalAccountProvider.META_MESSENGER, "page-1");
        verify(tenantResolutionService).unlink(ExternalAccountProvider.META_INSTAGRAM, "ig-1");
    }

    @Test
    void disconnectUnlinksOnlyMessengerForAMessengerOnlyRow() {
        MetaSocialCredential stored =
                new MetaSocialCredential("page-1", "Page", "e", "s", null, null);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));

        service.disconnect();

        verify(tenantResolutionService).unlink(ExternalAccountProvider.META_MESSENGER, "page-1");
        verify(tenantResolutionService, never())
                .unlink(ExternalAccountProvider.META_INSTAGRAM, null);
    }

    @Test
    void disconnectIsANoOpWhenNothingWasEverConnected() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        service.disconnect();

        verify(repository, never()).save(any());
        verifyNoInteractions(tenantResolutionService);
    }
}
