package com.agenticai.core.omnichannel.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.slack.dto.SlackActivationRequest;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Slack workspace (team_id) routes inbound events to exactly one tenant. Every app installed in a
 * workspace shares its team_id, so a second tenant connecting its own app there must be refused
 * rather than silently taking the link (and the first tenant's events) over.
 */
class SlackCredentialServiceTest {

    private static final String TEAM = "T12345678";

    private SlackCredentialRepository repository;
    private TenantResolutionService tenants;
    private TenantSchemaResolver schemaResolver;
    private SlackCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(SlackCredentialRepository.class);
        tenants = mock(TenantResolutionService.class);
        schemaResolver = mock(TenantSchemaResolver.class);
        SlackApiClient api = mock(SlackApiClient.class);
        EncryptionService encryption = mock(EncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(api.authTest(anyString()))
                .thenReturn(new SlackApiClient.AuthInfo(TEAM, "Acme", "UBOT00000"));
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(repository.save(any(SlackCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        service =
                new SlackCredentialService(
                        repository,
                        encryption,
                        tenants,
                        schemaResolver,
                        api,
                        mock(TenantLimits.class));
    }

    private static SlackActivationRequest request() {
        return new SlackActivationRequest("xoxb-token", "signing-secret");
    }

    @Test
    void aWorkspaceAlreadyLinkedToAnotherTenantIsRefusedAndTheLinkIsLeftAlone() {
        when(schemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_b");
        when(tenants.resolveTenantSchema(ExternalAccountProvider.SLACK, TEAM))
                .thenReturn(Optional.of("tenant_a"));

        ChannelConfigurationException refused =
                assertThrows(
                        ChannelConfigurationException.class, () -> service.activate(request()));

        assertEquals("slack", refused.getChannelName());
        verify(tenants, never()).link(any(), anyString(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void theSameTenantReconnectingItsOwnWorkspaceStillWorks() {
        when(schemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");
        when(tenants.resolveTenantSchema(ExternalAccountProvider.SLACK, TEAM))
                .thenReturn(Optional.of("tenant_a"));

        service.activate(request());

        verify(tenants).link(ExternalAccountProvider.SLACK, TEAM, "tenant_a");
    }

    @Test
    void movingTheCredentialToAnotherWorkspaceDropsTheOldWorkspaceLink() {
        when(schemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");
        when(tenants.resolveTenantSchema(ExternalAccountProvider.SLACK, TEAM))
                .thenReturn(Optional.empty());
        when(repository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(
                        Optional.of(
                                new SlackCredential(
                                        "T00OLDTEAM", "Old", "UBOT11111", "enc", "enc")));

        service.activate(request());

        verify(tenants).unlink(ExternalAccountProvider.SLACK, "T00OLDTEAM");
        verify(tenants).link(ExternalAccountProvider.SLACK, TEAM, "tenant_a");
    }

    @Test
    void reconnectingTheSameWorkspaceDoesNotUnlinkIt() {
        when(schemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");
        when(tenants.resolveTenantSchema(ExternalAccountProvider.SLACK, TEAM))
                .thenReturn(Optional.of("tenant_a"));
        when(repository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(
                        Optional.of(new SlackCredential(TEAM, "Acme", "UBOT00000", "enc", "enc")));

        service.activate(request());

        verify(tenants, never()).unlink(any(), anyString());
    }

    @Test
    void anUnlinkedWorkspaceIsLinkedToTheConnectingTenant() {
        when(schemaResolver.resolveCurrentTenantIdentifier()).thenReturn("tenant_b");
        when(tenants.resolveTenantSchema(ExternalAccountProvider.SLACK, TEAM))
                .thenReturn(Optional.empty());

        service.activate(request());

        verify(tenants).link(ExternalAccountProvider.SLACK, TEAM, "tenant_b");
    }
}
