package com.agenticai.core.platform;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.AppUser;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantStatus;
import com.agenticai.core.tenancy.UserRole;
import com.agenticai.core.workflow.WorkflowDefinitionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Cross-tenant roster + business metrics for the Ecosystem Maintenance console's {@code GET
 * /api/platform/tenants} - the super-admin's "how is every tenant doing" view.
 *
 * <p><b>Anonymous {@code /try} sandboxes are excluded</b> ({@code control_plane.tenant.is_demo}).
 * They are ACTIVE tenants with a real schema, and listing them made the admin tenant count track
 * landing-page traffic (crawlers included) instead of registrations. They are reported on their own
 * by {@link #tenantCounts()}.
 *
 * <p><b>Roster (2026-10-09):</b> every registered tenant that still exists - ACTIVE, SUSPENDED,
 * PROVISIONING and PROVISIONING_FAILED, never ERASED ({@link
 * TenantRepository#findRosterExcludingDemo}). Schema-derived counts are read only for
 * ACTIVE/SUSPENDED (see {@link #hasLiveSchema}); spend only for ACTIVE. The paragraph below
 * describes the original ACTIVE-only rule that still governs which tenants get per-schema lookups.
 *
 * <p>Only {@link TenantStatus#ACTIVE} tenants are included, the same filter {@code
 * com.agenticai.core.tenancy.TenantSchemaIterator#forEachActiveTenantSchema} already applies for
 * every other tenant-scoped background job in this codebase (agent/workflow counts only mean
 * anything for a tenant whose schema has actually been provisioned and migrated - a {@code
 * PROVISIONING}/{@code PROVISIONING_FAILED}/{@code SUSPENDED}/{@code ERASED} tenant's schema may
 * not even exist (anymore, for ERASED) or may be mid-provisioning, so querying it would either fail
 * outright or return meaningless data).
 *
 * <p>For each active tenant, {@link TenantContext} is set to that tenant's schema for the duration
 * of the per-tenant lookups (mirroring {@code TenantSchemaIterator}'s exact {@code
 * TenantContext.set}/{@code TenantContext.clear()} try/finally pattern), so {@link
 * AgentRepository}/ {@link WorkflowDefinitionRepository} - both routed by Hibernate's
 * schema-per-tenant multi-tenancy resolver - correctly see only that one tenant's rows.
 *
 * <p>Each of the three counts (agents, workflows, users) is fetched independently in its own
 * try/catch: one failing (e.g. Keycloak briefly unreachable for the user count) must never blank
 * the other two, and never stops the loop from moving on to the next tenant. {@code
 * metricsComplete} is {@code false} whenever any of the three failed for that tenant - the
 * corresponding count is reported as {@code 0} in that case (a real, flagged "couldn't determine
 * this," not a fabricated zero - see {@link PlatformTenantSummary}'s javadoc).
 *
 * <p>{@code @Profile("security")}: this service injects {@link IdentityUserAdminPort}, which is
 * itself {@code @Profile("security")}-gated (same as every other consumer of that client in this
 * codebase, e.g. {@code TenantProvisioningService}) - without this annotation here too, Spring
 * would fail to wire this bean whenever the security profile isn't active, since {@link
 * IdentityUserAdminPort} simply wouldn't exist yet.
 */
@Service
@Profile("security")
public class PlatformTenantMetricsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTenantMetricsService.class);
    private static final String SYSTEM_ACTOR = "system:platform-admin-metrics";

    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final IdentityUserAdminPort keycloakAdminClient;
    private final AppUserRepository appUserRepository;
    private final LlmCallLogRepository llmCallLogRepository;
    private final PlatformSpendService spendService;

    public PlatformTenantMetricsService(
            TenantRepository tenantRepository,
            AgentRepository agentRepository,
            WorkflowDefinitionRepository workflowDefinitionRepository,
            IdentityUserAdminPort keycloakAdminClient,
            AppUserRepository appUserRepository,
            LlmCallLogRepository llmCallLogRepository,
            PlatformSpendService spendService) {
        this.tenantRepository = tenantRepository;
        this.agentRepository = agentRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.keycloakAdminClient = keycloakAdminClient;
        this.appUserRepository = appUserRepository;
        this.llmCallLogRepository = llmCallLogRepository;
        this.spendService = spendService;
    }

    /**
     * V477 spend caps; optional (setter) so slim contexts and direct-constructor tests need none.
     */
    private com.agenticai.core.agents.spendcap.SpendCapService spendCapService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSpendCapService(
            com.agenticai.core.agents.spendcap.SpendCapService spendCapService) {
        this.spendCapService = spendCapService;
    }

    public List<PlatformTenantSummary> listTenantSummaries() {
        List<Tenant> roster = tenantRepository.findRosterExcludingDemo();
        Map<UUID, com.agenticai.core.agents.spendcap.SpendCapService.RosterCap> caps =
                new HashMap<>();
        if (spendCapService != null) {
            try {
                caps = spendCapService.rosterCaps();
            } catch (RuntimeException e) {
                log.warn("Failed to read spend caps for the platform roster", e);
            }
        }

        // Request-wide lookups, each fetched ONCE and indexed in memory. Each
        // failing leaves its map null, which the rows below turn into
        // metricsComplete=false / null fields - never a fabricated zero.
        //
        // Keycloak: one paged realm read grouped by the tenant_id attribute
        // (the SCHEMA name - see TenantProvisioningService's createUserForSignup,
        // Map.of("tenant_id", schemaName, ...); keying by tenant.getId() once
        // silently zeroed every count). Previously this was one full-realm
        // fetch PER TENANT, each capped at Keycloak's implicit 100 users.
        Map<String, List<KeycloakUserRepresentation>> usersByTenant = null;
        try {
            usersByTenant = keycloakAdminClient.listUsersGroupedByTenant();
        } catch (RuntimeException e) {
            log.warn("Failed to list Keycloak users for the platform roster", e);
        }
        Map<UUID, List<AppUser>> appUsersByTenant = null;
        Map<UUID, Instant> lastSeenByTenant = null;
        try {
            appUsersByTenant =
                    appUserRepository.findAll().stream()
                            .collect(Collectors.groupingBy(AppUser::getTenantId));
            lastSeenByTenant = new HashMap<>();
            for (AppUserRepository.TenantLastSeen row : appUserRepository.findLastSeenPerTenant()) {
                lastSeenByTenant.put(row.getTenantId(), row.getLastSeenAt());
            }
        } catch (RuntimeException e) {
            log.warn("Failed to read control-plane app_user rows for the platform roster", e);
        }
        Map<UUID, PlatformSpendService.TenantSpendTotals> spend = new HashMap<>();
        try {
            spend =
                    spendService.spendForTenants(
                            roster.stream()
                                    .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                                    .toList(),
                            PlatformSpendService.resolveRange(null, null));
        } catch (RuntimeException e) {
            log.warn("Failed to compute 30-day spend for the platform roster", e);
        }

        List<PlatformTenantSummary> summaries = new ArrayList<>();
        for (Tenant tenant : roster) {
            String schema = tenant.getSchemaName();
            boolean metricsComplete = usersByTenant != null;
            long agentCount = 0;
            long workflowCount = 0;
            long userCount =
                    usersByTenant == null
                            ? 0
                            : usersByTenant.getOrDefault(schema, List.of()).size();
            Instant lastLlmCall = null;
            boolean lastLlmCallKnown = false;

            if (hasLiveSchema(tenant)) {
                try {
                    TenantContext.set(TenantContext.of(schema, SYSTEM_ACTOR, Set.of()));

                    try {
                        agentCount = agentRepository.count();
                    } catch (RuntimeException e) {
                        metricsComplete = false;
                        log.warn("Failed to count agents for tenant schema '{}'", schema, e);
                    }

                    try {
                        workflowCount = workflowDefinitionRepository.count();
                    } catch (RuntimeException e) {
                        metricsComplete = false;
                        log.warn("Failed to count workflows for tenant schema '{}'", schema, e);
                    }

                    try {
                        lastLlmCall = llmCallLogRepository.findLatestStartedAt();
                        lastLlmCallKnown = true;
                    } catch (RuntimeException e) {
                        log.warn(
                                "Failed to read the last LLM call for tenant schema '{}'",
                                schema,
                                e);
                    }
                } finally {
                    TenantContext.clear();
                }
            }

            Instant lastSeen =
                    lastSeenByTenant == null ? null : lastSeenByTenant.get(tenant.getId());
            Instant lastActivity =
                    (lastSeenByTenant != null || lastLlmCallKnown)
                            ? PlatformTenantDetailController.latest(lastSeen, lastLlmCall)
                            : null;
            String ownerEmail =
                    appUsersByTenant == null
                            ? null
                            : ownerEmail(appUsersByTenant.get(tenant.getId()));
            PlatformSpendService.TenantSpendTotals tenantSpend = spend.get(tenant.getId());
            com.agenticai.core.agents.spendcap.SpendCapService.RosterCap cap =
                    caps.get(tenant.getId());

            summaries.add(
                    new PlatformTenantSummary(
                            tenant.getId(),
                            tenant.getName(),
                            tenant.getStatus().name(),
                            tenant.getPlan(),
                            agentCount,
                            workflowCount,
                            userCount,
                            metricsComplete,
                            tenant.isEmergencyStopped(),
                            tenant.getDataResidencyRegion(),
                            tenant.getCreatedAt(),
                            ownerEmail,
                            lastActivity,
                            tenantSpend == null ? null : tenantSpend.allProvidersUsd(),
                            tenantSpend == null ? null : tenantSpend.hostedPoolUsd(),
                            tenantSpend == null ? null : tenantSpend.totalCalls(),
                            cap == null ? null : cap.capUsd(),
                            cap == null ? null : cap.monthToDateUsd(),
                            cap == null ? null : cap.blocked()));
        }

        return summaries;
    }

    /**
     * ACTIVE and SUSPENDED tenants have a provisioned, migrated schema to count in;
     * PROVISIONING/PROVISIONING_FAILED may have none (or a half-built one), so their schema-derived
     * figures are reported as zero agents/ workflows - there is nothing to count yet - and no last
     * LLM call.
     */
    private static boolean hasLiveSchema(Tenant tenant) {
        return tenant.getStatus() == TenantStatus.ACTIVE
                || tenant.getStatus() == TenantStatus.SUSPENDED;
    }

    /** The earliest TENANT_ADMIN, else the earliest user of any role, else null. */
    static String ownerEmail(List<AppUser> users) {
        if (users == null || users.isEmpty()) {
            return null;
        }
        Comparator<AppUser> oldestFirst =
                Comparator.comparing(
                        AppUser::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        return users.stream()
                .filter(u -> u.getRole() == UserRole.TENANT_ADMIN)
                .min(oldestFirst)
                .or(() -> users.stream().min(oldestFirst))
                .map(AppUser::getEmail)
                .orElse(null);
    }

    /**
     * Registered tenants and demo sandboxes as separate figures - see {@link PlatformTenantCounts}.
     */
    public PlatformTenantCounts tenantCounts() {
        return new PlatformTenantCounts(
                tenantRepository.countActiveExcludingDemo(),
                tenantRepository.countDemoByStatus(TenantStatus.ACTIVE.name()),
                tenantRepository.countDemoByStatus(TenantStatus.SUSPENDED.name()));
    }
}
