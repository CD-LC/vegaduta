package com.agenticai.core.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared mocks for the {@link McpRegistryService} tests, which are split by behaviour area:
 * {@link McpRegistryServiceTest} (register/upsert, guards, toggles, status transitions), {@link
 * McpRegistryPlanGatingTest}, {@link McpRegistryDynamicConfigTest}, {@link
 * McpRegistryReviewGateTest} and {@link McpRegistrySec002Test}.
 */
abstract class McpRegistryServiceFixture {

    protected McpServerRegistrationRepository repository;
    protected EncryptionService encryptionService;
    protected TenantLimits tenantLimits;
    protected McpCatalogService catalogService;
    protected McpStaticReferenceUpgrader staticReferenceUpgrader;
    protected McpRegistryService service;

    @BeforeEach
    void setUp() {
        repository = mock(McpServerRegistrationRepository.class);
        encryptionService = mock(EncryptionService.class);
        tenantLimits = mock(TenantLimits.class);
        // Real catalog, not a mock - most tests register catalog names
        // (filesystem/github/brave-search/puppeteer) and must keep passing
        // the FREE-plan "built-in only" gate without every test having to
        // stub isCatalogEntry.
        catalogService = new McpCatalogService();
        // Real guards (with their default deny/allow lists), not mocks - they
        // are pure and their defaults are exactly what production wires; the
        // registrations used across this suite (npx + public config) must
        // pass them, which is itself a useful regression check.
        // A real resolver over an empty vault: these tests assert the
        // decrypt/merge behaviour, and none of their env values carry a
        // {{secret.X}} reference, so expansion must be a pass-through. A real
        // resolver (rather than a mock) is what proves that.
        // The dead-static-reference upgrade's availability lookup says "static serves everything"
        // here: the fixture's name-only registrations must keep landing as plain static
        // references, which is what every pre-upgrade test asserted. A test that wants the
        // upgrade builds its own upgrader with an unserving lookup (see McpRegistryServiceTest).
        staticReferenceUpgrader = new McpStaticReferenceUpgrader(
            repository, catalogService, new McpStdioCommandGuard(""), encryptionService,
            new SecretReferenceResolver(mock(SecretVaultService.class)),
            () -> (McpStaticServerAvailability) name -> true);
        service = new McpRegistryService(
            repository, encryptionService,
            new McpRemoteUrlGuard(""), new McpStdioCommandGuard(""),
            tenantLimits, catalogService,
            new SecretReferenceResolver(mock(SecretVaultService.class)),
            staticReferenceUpgrader);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // No TenantContext set for most tests - the plan-gate is a no-op
        // (same "no request context, not capped" posture as
        // AgentService#create) unless a test below explicitly sets one.
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Binds a TenantContext for {@code tenantId} whose plan is {@code plan}. */
    protected void setTenant(String tenantId, TenantPlan plan) {
        TenantContext.set(TenantContext.of(tenantId, "test-subject", Set.of()));
        when(tenantLimits.plan(tenantId)).thenReturn(plan);
        when(tenantLimits.accountType(tenantId)).thenReturn(null);
    }
}
