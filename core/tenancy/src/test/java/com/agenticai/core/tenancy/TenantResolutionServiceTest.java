package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * No test previously existed for {@link TenantResolutionService} - the
 * cross-tenant lookup every inbound channel webhook (Twilio, Meta, Slack,
 * etc - see {@link ExternalAccountProvider}) depends on to answer "which
 * tenant schema does this belong to" before it can do anything schema-scoped
 * at all. A bug here is a direct tenant-isolation risk: resolving to the
 * wrong schema, or {@link #link} leaving a stale cross-tenant pointer behind,
 * means one tenant's webhook traffic could be attributed to (and processed
 * against) a different tenant's schema. Covers: the plain lookup in both
 * directions, first-time linking, and the upsert/repoint path the class's
 * own javadoc calls out as deliberate but unenforced against reuse.
 */
class TenantResolutionServiceTest {

    private static final String EXTERNAL_ACCOUNT_ID = "AC_twilio_123";

    private ChannelAccountLinkRepository repository;
    private TenantResolutionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChannelAccountLinkRepository.class);
        service = new TenantResolutionService(repository);
    }

    @Test
    void resolveTenantSchemaReturnsEmptyWhenNoLinkExistsForThisExternalAccount() {
        when(repository.findByProviderAndExternalAccountId(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID))
            .thenReturn(Optional.empty());

        Optional<String> resolved = service.resolveTenantSchema(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID);

        assertFalse(resolved.isPresent());
    }

    @Test
    void resolveTenantSchemaReturnsTheLinkedTenantSchema() {
        ChannelAccountLink link = new ChannelAccountLink(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID, "acme");
        when(repository.findByProviderAndExternalAccountId(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID))
            .thenReturn(Optional.of(link));

        Optional<String> resolved = service.resolveTenantSchema(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID);

        assertTrue(resolved.isPresent());
        assertEquals("acme", resolved.get());
    }

    @Test
    void resolveTenantSchemaDoesNotConflateDifferentProvidersWithTheSameExternalAccountId() {
        // Same raw id string, but the WORKFLOW_WEBHOOK provider has never linked it - only
        // TWILIO has. A lookup for the other provider must not return TWILIO's tenant.
        when(repository.findByProviderAndExternalAccountId(ExternalAccountProvider.WORKFLOW_WEBHOOK, EXTERNAL_ACCOUNT_ID))
            .thenReturn(Optional.empty());

        Optional<String> resolved = service.resolveTenantSchema(ExternalAccountProvider.WORKFLOW_WEBHOOK, EXTERNAL_ACCOUNT_ID);

        assertFalse(resolved.isPresent());
        verify(repository, never()).findByProviderAndExternalAccountId(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID);
    }

    @Test
    void linkCreatesANewLinkWhenNoneExistsForThisExternalAccount() {
        when(repository.findByProviderAndExternalAccountId(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID))
            .thenReturn(Optional.empty());

        service.link(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID, "acme");

        ArgumentCaptor<ChannelAccountLink> captor = ArgumentCaptor.forClass(ChannelAccountLink.class);
        verify(repository, times(1)).save(captor.capture());
        ChannelAccountLink saved = captor.getValue();
        assertEquals(ExternalAccountProvider.TWILIO, saved.getProvider());
        assertEquals(EXTERNAL_ACCOUNT_ID, saved.getExternalAccountId());
        assertEquals("acme", saved.getTenantSchema());
    }

    @Test
    void linkRepointsAnExistingLinkToADifferentTenantSchemaRatherThanLeavingItStale() {
        // The scenario the class's javadoc explicitly calls out: this external account id was
        // previously linked to a different tenant's schema, and must be repointed - not left
        // stale, and not inserted as a second row (which would violate the unique constraint on
        // (provider, external_account_id) and, worse, leave two tenants both believing they own
        // this external account).
        ChannelAccountLink existing = new ChannelAccountLink(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID, "old-tenant");
        when(repository.findByProviderAndExternalAccountId(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID))
            .thenReturn(Optional.of(existing));

        service.link(ExternalAccountProvider.TWILIO, EXTERNAL_ACCOUNT_ID, "new-tenant");

        ArgumentCaptor<ChannelAccountLink> captor = ArgumentCaptor.forClass(ChannelAccountLink.class);
        verify(repository, times(1)).save(captor.capture());
        // Same row (identity, not just equal fields) was updated in place, not a new one created.
        assertSame(existing, captor.getValue());
        assertEquals("new-tenant", captor.getValue().getTenantSchema());
        assertEquals("new-tenant", existing.getTenantSchema());
    }

    @Test
    void linkAlwaysLooksUpByBothProviderAndExternalAccountIdNeverByExternalAccountIdAlone() {
        when(repository.findByProviderAndExternalAccountId(any(), any())).thenReturn(Optional.empty());

        service.link(ExternalAccountProvider.SLACK, "T12345", "acme");

        verify(repository).findByProviderAndExternalAccountId(ExternalAccountProvider.SLACK, "T12345");
    }
}
