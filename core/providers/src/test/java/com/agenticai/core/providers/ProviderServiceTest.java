package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.dto.ProviderActivationRequest;
import com.agenticai.core.providers.dto.ProviderAvailabilityResponse;
import com.agenticai.core.providers.dto.ProviderResponse;
import com.agenticai.core.providers.validation.ProviderNotSupportedException;
import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.providers.validation.ProviderValidator;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ProviderService#activate} is the one write path a tenant reaches to
 * bring their own LLM key online, and per its own javadoc has real
 * documented history of a governance gap ("even provider page should not be
 * editable" for FREE-tier tenants) that this suite locks in as a regression
 * test, not just a happy-path check. Had zero coverage before this suite.
 *
 * <p>{@link #checkAvailability} coverage added alongside {@code
 * ProviderAvailabilityResponse} - see that DTO's javadoc for why {@link
 * ProviderService#listAll}'s length alone was the wrong signal {@code
 * web/}'s AgentWorkbench create-gate relied on for a FREE tenant.
 *
 * <p>Uses real {@link TenantContext} (not a mock - it is a static/ThreadLocal
 * holder, same as every other test in this codebase that touches tenant
 * scoping relies on setting/clearing it directly) rather than mocking a
 * static class.
 */
class ProviderServiceTest {

    private LlmProviderCredentialRepository repository;
    private EncryptionService encryptionService;
    private ProviderValidator anthropicValidator;
    private ExternalProviderCredentialSource sarvamSource;
    private TenantLimits tenantLimits;
    private PlatformHostedLlmProviderService platformHostedLlmProviderService;
    private ProviderService service;

    @BeforeEach
    void setUp() {
        repository = mock(LlmProviderCredentialRepository.class);
        encryptionService = mock(EncryptionService.class);
        anthropicValidator = mock(ProviderValidator.class);
        when(anthropicValidator.supportedProvider()).thenReturn(ProviderName.ANTHROPIC);
        // ADR-0011 phase 2: Sarvam is merged via its contributed ExternalProviderCredentialSource.
        sarvamSource = mock(ExternalProviderCredentialSource.class);
        when(sarvamSource.providerName()).thenReturn(ProviderName.SARVAM);
        tenantLimits = mock(TenantLimits.class);
        platformHostedLlmProviderService = mock(PlatformHostedLlmProviderService.class);
        service = new ProviderService(
            repository, encryptionService, List.of(anthropicValidator), List.of(sarvamSource), tenantLimits,
            platformHostedLlmProviderService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ProviderActivationRequest anthropicRequest() {
        return new ProviderActivationRequest(ProviderName.ANTHROPIC, "sk-ant-real-key");
    }

    // --- activate(): FREE-tier gate -----------------------------------------

    @Test
    void activateRejectsFreeTierTenantsWithoutEverCallingTheValidator() {
        TenantContext.set(TenantContext.of("tenant_free", "user-1", Set.of()));
        // FREE seeds byok_allowed=false; the gate now reads the entitlement,
        // not a hardcoded plan check, so a platform-admin can change it.
        when(tenantLimits.byokAllowed("tenant_free")).thenReturn(false);

        assertThrows(TenantLimitExceededException.class, () -> service.activate(anthropicRequest()));

        verify(anthropicValidator, never()).validate(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void activateAllowsNonFreeTiersThroughToValidation() {
        TenantContext.set(TenantContext.of("tenant_premium", "user-1", Set.of()));
        when(tenantLimits.byokAllowed("tenant_premium")).thenReturn(true);
        when(repository.findByProviderName(ProviderName.ANTHROPIC)).thenReturn(Optional.empty());
        when(encryptionService.encrypt("sk-ant-real-key")).thenReturn("cipher");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.activate(anthropicRequest());

        verify(anthropicValidator).validate("sk-ant-real-key");
    }

    @Test
    void activateWithNoTenantContextIsNotTreatedAsFreeTier() {
        // No HTTP request context (e.g. a future non-request caller) - must
        // not accidentally fall into the not-allowed block, same "uncapped
        // rather than NPE/misclassify" posture documented on WorkflowService's
        // own tenant-scoped limit checks.
        when(repository.findByProviderName(ProviderName.ANTHROPIC)).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenReturn("cipher");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(service.activate(anthropicRequest()) != null);
        verify(tenantLimits, never()).byokAllowed(any());
    }

    // --- activate(): unsupported provider -----------------------------------

    @Test
    void activateThrowsForAProviderWithNoRegisteredValidator() {
        ProviderActivationRequest request = new ProviderActivationRequest(ProviderName.OPENAI, "key");

        assertThrows(ProviderNotSupportedException.class, () -> service.activate(request));

        verify(repository, never()).save(any());
    }

    // --- activate(): validation failure persists nothing --------------------

    @Test
    void activatePersistsNothingWhenValidationRejectsTheKey() {
        org.mockito.Mockito.doThrow(new ProviderValidationException("rejected"))
            .when(anthropicValidator).validate("sk-ant-real-key");

        assertThrows(ProviderValidationException.class, () -> service.activate(anthropicRequest()));

        verify(repository, never()).save(any());
        verify(encryptionService, never()).encrypt(anyString());
    }

    // --- activate(): new vs reactivated credential ---------------------------

    @Test
    void activateCreatesANewCredentialWhenNoneExistsYetForThisProvider() {
        when(repository.findByProviderName(ProviderName.ANTHROPIC)).thenReturn(Optional.empty());
        when(encryptionService.encrypt("sk-ant-real-key")).thenReturn("cipher");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderResponse response = service.activate(anthropicRequest());

        assertEquals(ProviderName.ANTHROPIC, response.providerName());
        assertEquals(ProviderStatus.ACTIVE, response.status());
    }

    @Test
    void activateReactivatesAnExistingRowInsteadOfInsertingADuplicate() {
        // The unique constraint on provider_name means a second activation
        // of the same provider MUST update the existing row, not attempt a
        // second insert that would violate it.
        LlmProviderCredential existing = mock(LlmProviderCredential.class);
        when(existing.getProviderName()).thenReturn(ProviderName.ANTHROPIC);
        when(existing.getStatus()).thenReturn(ProviderStatus.ACTIVE);
        when(repository.findByProviderName(ProviderName.ANTHROPIC)).thenReturn(Optional.of(existing));
        when(encryptionService.encrypt("sk-ant-real-key")).thenReturn("new-cipher");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.activate(anthropicRequest());

        verify(existing).reactivate("new-cipher", ProviderStatus.ACTIVE);
        verify(repository, times(1)).save(existing);
    }

    // --- listAll(): Sarvam merge --------------------------------------------

    @Test
    void listAllMergesInAnActiveSarvamCredentialEvenThoughItLivesInADifferentTable() {
        when(repository.findAll()).thenReturn(List.of());
        UUID sarvamId = UUID.randomUUID();
        Instant now = Instant.now();
        when(sarvamSource.currentCredential()).thenReturn(
            Optional.of(new ProviderResponse(sarvamId, ProviderName.SARVAM, ProviderStatus.ACTIVE, now, now, null)));

        List<ProviderResponse> all = service.listAll();

        assertEquals(1, all.size());
        assertEquals(ProviderName.SARVAM, all.get(0).providerName());
        assertEquals(ProviderStatus.ACTIVE, all.get(0).status());
    }

    @Test
    void listAllOmitsSarvamEntirelyWhenNoCredentialHasEverBeenActivated() {
        when(repository.findAll()).thenReturn(List.of());
        when(sarvamSource.currentCredential()).thenReturn(Optional.empty());

        List<ProviderResponse> all = service.listAll();

        assertTrue(all.isEmpty());
    }

    @Test
    void listAllPassesTheContributedSourcesStatusThroughUnchanged() {
        // The INACTIVE -> INVALID mapping itself now lives in (and is tested by)
        // sarvam.SarvamProviderCredentialSource; the kernel just merges.
        when(repository.findAll()).thenReturn(List.of());
        Instant now = Instant.now();
        when(sarvamSource.currentCredential()).thenReturn(
            Optional.of(new ProviderResponse(UUID.randomUUID(), ProviderName.SARVAM, ProviderStatus.INVALID, now, now, null)));

        List<ProviderResponse> all = service.listAll();

        assertEquals(ProviderStatus.INVALID, all.get(0).status());
    }

    // --- checkAvailability(): the AgentWorkbench create-gate fix ------------

    @Test
    void freeTenantWithNoByokIsAvailableViaThePlatformPoolWhenAProviderIsEnabled() {
        TenantContext.set(TenantContext.of("tenant_free", "user-1", Set.of()));
        when(tenantLimits.usesPlatformPool("tenant_free")).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of());
        when(sarvamSource.currentCredential()).thenReturn(Optional.empty());
        when(platformHostedLlmProviderService.resolveTopPriorityEnabled())
            .thenReturn(Optional.of(mock(PlatformHostedLlmProviderService.HostedProviderSelection.class)));

        ProviderAvailabilityResponse response = service.checkAvailability();

        assertFalse(response.hasByokProvider());
        assertTrue(response.platformHostedAvailable());
        assertTrue(response.canCreateAgents());
    }

    @Test
    void freeTenantWithEmptyPlatformPoolCannotCreateAgents() {
        // This is exactly the "No active LLM provider yet" scenario this
        // endpoint exists to report accurately instead of silently blocking.
        TenantContext.set(TenantContext.of("tenant_free", "user-1", Set.of()));
        when(tenantLimits.usesPlatformPool("tenant_free")).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of());
        when(sarvamSource.currentCredential()).thenReturn(Optional.empty());
        when(platformHostedLlmProviderService.resolveTopPriorityEnabled()).thenReturn(Optional.empty());

        ProviderAvailabilityResponse response = service.checkAvailability();

        assertFalse(response.hasByokProvider());
        assertFalse(response.platformHostedAvailable());
        assertFalse(response.canCreateAgents());
    }

    @Test
    void premiumTenantWithAByokCredentialIsAvailableEvenIfThePlatformPoolIsEmpty() {
        TenantContext.set(TenantContext.of("tenant_premium", "user-1", Set.of()));
        when(tenantLimits.usesPlatformPool("tenant_premium")).thenReturn(false);
        LlmProviderCredential credential = mock(LlmProviderCredential.class);
        when(credential.getProviderName()).thenReturn(ProviderName.ANTHROPIC);
        when(credential.getStatus()).thenReturn(ProviderStatus.ACTIVE);
        when(repository.findAll()).thenReturn(List.of(credential));
        when(sarvamSource.currentCredential()).thenReturn(Optional.empty());

        ProviderAvailabilityResponse response = service.checkAvailability();

        assertTrue(response.hasByokProvider());
        assertTrue(response.canCreateAgents());
        // Not eligible for the platform pool (PREMIUM without opting in) -
        // never even asked the pool whether it has a row.
        verify(platformHostedLlmProviderService, never()).resolveTopPriorityEnabled();
    }

    @Test
    void premiumTenantWithNoByokAndNoUsePlatformLlmOptInCannotCreateAgents() {
        TenantContext.set(TenantContext.of("tenant_premium", "user-1", Set.of()));
        when(tenantLimits.usesPlatformPool("tenant_premium")).thenReturn(false);
        when(repository.findAll()).thenReturn(List.of());
        when(sarvamSource.currentCredential()).thenReturn(Optional.empty());

        ProviderAvailabilityResponse response = service.checkAvailability();

        assertFalse(response.canCreateAgents());
        verify(platformHostedLlmProviderService, never()).resolveTopPriorityEnabled();
    }

    @Test
    void premiumTenantThatOptedIntoUsePlatformLlmIsAvailableViaThePool() {
        // Direct-feedback opt-in ("every non free tenant should get an
        // option to select use platform llm"), or an active complimentary
        // grant - this endpoint no longer spells the rule out, it asks
        // TenantLimits#usesPlatformPool, the same single source
        // AgentChatService#buildChatClientFor asks.
        TenantContext.set(TenantContext.of("tenant_premium", "user-1", Set.of()));
        when(tenantLimits.usesPlatformPool("tenant_premium")).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of());
        when(sarvamSource.currentCredential()).thenReturn(Optional.empty());
        when(platformHostedLlmProviderService.resolveTopPriorityEnabled())
            .thenReturn(Optional.of(mock(PlatformHostedLlmProviderService.HostedProviderSelection.class)));

        ProviderAvailabilityResponse response = service.checkAvailability();

        assertTrue(response.platformHostedAvailable());
        assertTrue(response.canCreateAgents());
    }

    @Test
    void noTenantContextIsNeverEligibleForThePlatformPool() {
        // Matches every other tenant-scoped check in this codebase: no HTTP
        // request context means no plan to resolve, so this can only ever
        // report BYOK availability.
        when(repository.findAll()).thenReturn(List.of());
        when(sarvamSource.currentCredential()).thenReturn(Optional.empty());

        ProviderAvailabilityResponse response = service.checkAvailability();

        assertFalse(response.platformHostedAvailable());
        assertFalse(response.canCreateAgents());
        verify(tenantLimits, never()).plan(any());
        verify(platformHostedLlmProviderService, never()).resolveTopPriorityEnabled();
    }
}
