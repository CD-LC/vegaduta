package com.agenticai.core.tenancy;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.HostedPoolAvailability;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one concrete implementation of {@link TenantLimits} - see that
 * interface's javadoc for why it lives in {@code security} instead of here
 * despite this class needing the actual {@link Tenant} row to answer it.
 *
 * <p>{@code tenantId} in every method here is the schema name (the JWT
 * {@code tenant_id} claim), so every lookup goes through {@link
 * TenantRepository#findBySchemaName}, never {@link TenantRepository#findById}
 * - the same distinction {@code AppUserRepository.findByTenantId(UUID)}
 * elsewhere in this module is keyed differently for (that one wants the
 * tenant's actual primary key, not the schema name).
 *
 * <p><b>A2-PR1 refactor:</b> {@link #maxUsers}/{@link #maxAgents}/{@link
 * #maxWorkflows} used to return hardcoded per-plan constants
 * (FREE_MAX_USERS=2 etc.) directly from a {@code switch} on {@link
 * #plan(String)}. They now delegate to {@link PlanLimitsService}, which
 * reads the data-driven {@code control_plane.plan_limits} table
 * (Redis-cached - see that class's javadoc) so a platform-admin can change
 * per-plan caps at runtime without a redeploy. The {@code -1 == "no cap"}
 * sentinel {@code plan_limits} rows use is translated back into this
 * interface's existing {@code Integer.MAX_VALUE}/{@code Optional.empty()}
 * shape right here, so every caller of this class (AgentChatService,
 * AgentService, WorkflowService, UserAdminController, ... - grepped across
 * the repo before this refactor, all read {@link TenantLimits} only
 * through this interface) is completely unaffected: same method
 * signatures, same return semantics, same public contract. {@link
 * #accountType}/{@link #experienceMode}/{@link #plan}/{@link
 * #usePlatformLlm} are unchanged by this refactor - those already read
 * straight off the {@link Tenant} row and were never part of the
 * hardcoded-constants problem this PR fixes.
 */
@Component
class TenantLimitsServiceImpl implements TenantLimits {

    private final TenantRepository tenantRepository;
    private final PlanLimitsService planLimitsService;
    private final FreeModeService freeModeService;

    /**
     * What a tenant that never made an explicit LLM-source choice gets:
     * {@code platform-pool} (hosted SaaS default), {@code byok}, or {@code auto}
     * = the pool when this deployment has one configured, else the tenant's own
     * key. See {@code app.llm.default-source}.
     */
    private final String defaultSource;
    private final ObjectProvider<HostedPoolAvailability> poolAvailability;

    /** Test/legacy constructor: hosted-SaaS behaviour (pool is the default, no availability probe). */
    TenantLimitsServiceImpl(TenantRepository tenantRepository, PlanLimitsService planLimitsService, FreeModeService freeModeService) {
        this(tenantRepository, planLimitsService, freeModeService, null, "platform-pool");
    }

    @Autowired
    TenantLimitsServiceImpl(
        TenantRepository tenantRepository,
        PlanLimitsService planLimitsService,
        FreeModeService freeModeService,
        ObjectProvider<HostedPoolAvailability> poolAvailability,
        @Value("${app.llm.default-source:auto}") String defaultSource
    ) {
        this.tenantRepository = tenantRepository;
        this.planLimitsService = planLimitsService;
        this.freeModeService = freeModeService;
        this.poolAvailability = poolAvailability;
        this.defaultSource = defaultSource == null ? "auto" : defaultSource.trim().toLowerCase(Locale.ROOT);
    }

    /** The implicit (no explicit choice) source: true = platform pool, false = tenant BYOK. */
    private boolean defaultIsPool() {
        return switch (defaultSource) {
            case "platform-pool", "pool", "platform" -> true;
            case "byok" -> false;
            // auto (and any unrecognised value): pool only where one exists, so a
            // self-hosted install with no pool degrades to the tenant's own key.
            default -> {
                HostedPoolAvailability availability = poolAvailability == null ? null : poolAvailability.getIfAvailable();
                yield availability == null || availability.isPoolConfigured();
            }
        };
    }

    @Override
    public int maxUsers(String tenantId) {
        int maxUsers = planLimitsService.getLimits(tenantId).maxUsers();
        return maxUsers < 0 ? Integer.MAX_VALUE : maxUsers;
    }

    @Override
    public Optional<Integer> maxAgents(String tenantId) {
        if (isSharedDemoTenant(tenantId)) {
            return Optional.empty();
        }
        int maxAgents = planLimitsService.getLimits(tenantId).maxAgents();
        return maxAgents < 0 ? Optional.empty() : Optional.of(maxAgents);
    }

    @Override
    public Optional<Integer> maxWorkflows(String tenantId) {
        if (isSharedDemoTenant(tenantId)) {
            return Optional.empty();
        }
        int maxWorkflows = planLimitsService.getLimits(tenantId).maxWorkflows();
        return maxWorkflows < 0 ? Optional.empty() : Optional.of(maxWorkflows);
    }

    /**
     * The shared demo sandbox schema (/try) is one tenant row serving every
     * anonymous visitor at once, so a per-schema plan cap would cap the
     * whole shop window. The studio applies per-session and per-IP caps of
     * its own ({@code DemoStudioService}); here the plan cap steps aside.
     * Failure-safe: any lookup problem means "not demo", i.e. the cap stays.
     */
    @Override
    public boolean isSharedDemoTenant(String tenantId) {
        if (tenantId == null) {
            return false;
        }
        try {
            return tenantRepository.isDemoSchema(tenantId).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public AccountType accountType(String tenantId) {
        return resolve(tenantId).getAccountType();
    }

    @Override
    public ExperienceMode experienceMode(String tenantId) {
        return resolve(tenantId).getExperienceMode();
    }

    /**
     * The tenant's EFFECTIVE plan for feature gating - in free mode ({@link
     * FreeModeService}) this is {@link TenantPlan#UNLIMITED} for every tenant
     * without touching the {@code Tenant} row, which is what unlocks
     * editability ({@code EditLockPolicy}), plan-gated guardrails and every
     * other {@code plan()}-reading check. The stored plan is still the truth
     * for reporting - callers that want it read {@code Tenant} directly.
     */
    @Override
    public TenantPlan plan(String tenantId) {
        if (freeModeService.isEnabled()) {
            return TenantPlan.UNLIMITED;
        }
        return resolve(tenantId).getPlan();
    }

    /** The row's plan, free mode or not - see the interface javadoc for who may read this. */
    @Override
    public TenantPlan storedPlan(String tenantId) {
        return resolve(tenantId).getPlan();
    }

    @Override
    public boolean usePlatformLlm(String tenantId) {
        return resolve(tenantId).isUsePlatformLlm();
    }

    /**
     * The one body of the hosted-pool rule - see {@link
     * TenantLimits#usesPlatformPool(String, TenantPlan)} for why an active
     * complimentary grant counts, and why that is computed here instead of
     * being written onto {@code Tenant.usePlatformLlm} at grant time.
     *
     * <p>Ordered so the hot path stays as cheap as it was before this method
     * existed. {@code AgentChatService} calls this per turn with the plan it
     * already resolved: a FREE tenant returns on the first line, touching no
     * database at all (exactly what {@code plan == FREE || ...} short-circuited
     * to), and every other tenant costs the single {@link #resolve} that the
     * old {@code usePlatformLlm(tenantId)} term was already paying for. The
     * opt-in and the grant are read off that one row rather than through two
     * separate interface calls, which would have been two {@code
     * findBySchemaName} round trips for the same turn.
     */
    @Override
    public boolean usesPlatformPool(String tenantId, TenantPlan plan) {
        if (tenantId == null) {
            return false;
        }
        if (plan == TenantPlan.FREE) {
            // FREE has always meant "hosted pool"; it now honours app.llm.default-source so a
            // self-hosted install with no pool routes FREE tenants to their own key instead of
            // an empty pool (platform-pool mode = the old unconditional true).
            return defaultIsPool();
        }
        Tenant tenant = resolve(tenantId);
        // Stored-FREE short-circuit only while free mode is OFF. Under free mode the
        // effective plan reads UNLIMITED and the UI unlocks "Your own provider", so a
        // null-plan caller (GET /api/providers/availability, AgentService.usesPlatformLlm)
        // must reach the explicit-choice clause below like the chat path does - otherwise
        // "Switch to BYOK" saves but availability keeps answering "pool" and the switch
        // never shows as selected (prod report 2026-09-24).
        if (plan == null && tenant.getPlan() == TenantPlan.FREE && !freeModeService.isEnabled()) {
            return defaultIsPool();
        }
        if (tenant.isUsePlatformLlm()) {
            return true;
        }
        // Platform pool is the DEFAULT for every tenant (2026-09-29):
        // absent an explicit choice (llm_source_explicit = false) the hosted pool
        // serves the call, whatever the plan or grant state. BYOK / edge are
        // opt-in, recorded the moment the admin picks one - setUsePlatformLlm
        // flips llm_source_explicit, and once it is set use_platform_llm alone
        // decides (the toggle branch above already returned true for "pool").
        // Existing paying tenants that were on their own key are marked explicit
        // BYOK by V366 so this default does not silently re-bill them.
        // Hosted-pool spend stays bounded by FreeTierUsageLimiterService's caps.
        // Self-hosters: app.llm.default-source=auto (the OSS default) makes this
        // "pool if one is configured, else the tenant's own key" - see defaultIsPool.
        return !tenant.isLlmSourceExplicit() && defaultIsPool();
    }

    @Override
    public boolean byokAllowed(String tenantId) {
        // Where the deployment has no hosted pool (app.llm.default-source=byok, or auto with none
        // configured) BYOK is the only way to get a model, so the plan gate must not lock FREE
        // tenants out of it.
        return planLimitsService.getLimits(tenantId).byokAllowed() || !defaultIsPool();
    }

    @Override
    public boolean platformPoolAvailable() {
        return defaultIsPool();
    }

    @Override
    public boolean whatsappAllowed(String tenantId) {
        return planLimitsService.getLimits(tenantId).whatsappAllowed();
    }

    @Override
    public boolean whiteLabel(String tenantId) {
        return planLimitsService.getLimits(tenantId).whiteLabel();
    }

    @Override
    public Optional<String> brandDisplayName(String tenantId) {
        // Effective form (see the interface javadoc): the entitlement gates
        // the READ, so a downgrade off a white-label plan instantly restores
        // platform branding without clearing the stored value.
        if (!whiteLabel(tenantId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(resolve(tenantId).getBrandDisplayName()).filter(s -> !s.isBlank());
    }

    @Override
    public Optional<String> brandLogoUrl(String tenantId) {
        if (!whiteLabel(tenantId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(resolve(tenantId).getBrandLogoUrl()).filter(s -> !s.isBlank());
    }

    @Override
    public Optional<Integer> maxKbCollections(String tenantId) {
        if (isSharedDemoTenant(tenantId)) {
            return Optional.empty();
        }
        int maxKbCollections = planLimitsService.getLimits(tenantId).maxKbCollections();
        return maxKbCollections < 0 ? Optional.empty() : Optional.of(maxKbCollections);
    }

    @Override
    public boolean liveVisionAllowed(String tenantId) {
        return planLimitsService.getLimits(tenantId).liveVisionAllowed();
    }

    @Override
    public boolean externalChannelsAllowed(String tenantId) {
        return planLimitsService.getLimits(tenantId).externalChannelsAllowed();
    }

    @Override
    public boolean developerApiAllowed(String tenantId) {
        return planLimitsService.getLimits(tenantId).developerApiAllowed();
    }

    @Override
    public boolean ssoAllowed(String tenantId) {
        return planLimitsService.getLimits(tenantId).ssoAllowed();
    }

    @Override
    public boolean scimAllowed(String tenantId) {
        return planLimitsService.getLimits(tenantId).scimAllowed();
    }

    @Override
    public boolean workflowExportAllowed(String tenantId) {
        return planLimitsService.getLimits(tenantId).workflowExportAllowed();
    }

    @Override
    public boolean automationAllowed(String tenantId) {
        return planLimitsService.getLimits(tenantId).automationAllowed();
    }

    private Tenant resolve(String tenantId) {
        return tenantRepository.findBySchemaName(tenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No Tenant row for schema '" + tenantId + "' - TenantContext claims a tenant that doesn't exist"));
    }
}
