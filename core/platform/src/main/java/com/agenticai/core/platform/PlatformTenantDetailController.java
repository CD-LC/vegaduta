package com.agenticai.core.platform;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.security.DataResidencyRegion;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantStatus;
import com.agenticai.core.workflow.WorkflowDefinitionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only per-tenant control page backend - {@code /api/platform/tenant-detail/**}, gated by the
 * blanket {@code /api/platform/** -> ROLE_platform-admin} rule.
 *
 * <p><b>Lifecycle, honestly.</b> What the request path actually enforces is {@code
 * TenantStatusGuardImpl#computeUsable}: a tenant is usable only when {@code status == ACTIVE &&
 * !emergencyStopped}. {@code Tenant} has no method that sets {@code SUSPENDED} (only
 * provisioning/erasure paths move status), so the operator-facing "suspend / reactivate" control IS
 * the emergency stop ({@code POST /api/platform/tenants/{id}/emergency-stop|emergency-resume},
 * already in {@code PlatformController}). This controller therefore adds no mutation; it reports
 * both fields plus a derived {@code accessState} so the page can show exactly what the guard will
 * decide.
 *
 * <p><b>Tenant context.</b> The operator token is tenant-less, so per-tenant tables (agents,
 * workflows, llm_call_log) are read inside {@link #asTenant} bound to the target's schema name;
 * control_plane reads need no binding. Tenants that are not ACTIVE get no per-schema counts (the
 * schema may be absent or mid-provisioning), same rule as {@link PlatformTenantMetricsService}.
 * Each metric is fetched independently; a failure is reported in {@code unavailable} rather than as
 * a fabricated zero.
 */
@RestController
@RequestMapping("/api/platform/tenant-detail")
@Profile("security")
public class PlatformTenantDetailController {

    private static final Logger log = LoggerFactory.getLogger(PlatformTenantDetailController.class);
    private static final String SYSTEM_ACTOR = "system:platform-tenant-detail";

    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final WorkflowDefinitionRepository workflowRepository;
    private final IdentityUserAdminPort keycloak;
    private final FreeTierUsageLimiterService usageLimiter;
    private final LlmCallLogRepository llmCallLogRepository;
    private final AppUserRepository appUserRepository;
    private final PlatformSpendService spendService;

    public PlatformTenantDetailController(
            TenantRepository tenantRepository,
            AgentRepository agentRepository,
            WorkflowDefinitionRepository workflowRepository,
            IdentityUserAdminPort keycloak,
            FreeTierUsageLimiterService usageLimiter,
            LlmCallLogRepository llmCallLogRepository,
            AppUserRepository appUserRepository,
            PlatformSpendService spendService) {
        this.tenantRepository = tenantRepository;
        this.agentRepository = agentRepository;
        this.workflowRepository = workflowRepository;
        this.keycloak = keycloak;
        this.usageLimiter = usageLimiter;
        this.llmCallLogRepository = llmCallLogRepository;
        this.appUserRepository = appUserRepository;
        this.spendService = spendService;
    }

    /**
     * Picker roster: every non-demo tenant INCLUDING lifecycle states the main roster hides
     * (SUSPENDED, PROVISIONING, PROVISIONING_FAILED). ERASED tenants are omitted. No per-tenant
     * counts here - that is the detail call.
     */
    @GetMapping
    public List<TenantRef> list() {
        // One demo-excluding query (2026-10-09): the per-status findByStatus
        // calls this replaced included every SUSPENDED legacy /try sandbox.
        return tenantRepository.findRosterExcludingDemo().stream()
                .sorted(
                        Comparator.comparing(
                                (Tenant t) -> String.valueOf(t.getName()).toLowerCase(Locale.ROOT)))
                .map(
                        t ->
                                new TenantRef(
                                        t.getId(),
                                        t.getName(),
                                        t.getStatus().name(),
                                        t.isEmergencyStopped()))
                .toList();
    }

    @GetMapping("/{tenantId}")
    public TenantDetail get(@PathVariable("tenantId") UUID tenantId) {
        Tenant t =
                tenantRepository
                        .findById(tenantId)
                        .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
        String schema = t.getSchemaName();
        List<String> unavailable = new ArrayList<>();
        boolean active = t.getStatus() == TenantStatus.ACTIVE;

        Long users = metric("users", unavailable, () -> (long) keycloak.countUsersByTenant(schema));
        Long agents = null;
        Long workflows = null;
        Instant lastLlmCall = null;
        boolean lastCallKnown = false;
        BigDecimal spend30d = null;
        if (active) {
            agents = metric("agents", unavailable, () -> asTenant(schema, agentRepository::count));
            workflows =
                    metric(
                            "workflows",
                            unavailable,
                            () -> asTenant(schema, workflowRepository::count));
            try {
                // One MAX() aggregate - this used to load 200 full llm_call_log rows to read one
                // timestamp.
                lastLlmCall = asTenant(schema, llmCallLogRepository::findLatestStartedAt);
                lastCallKnown = true;
            } catch (RuntimeException e) {
                unavailable.add("lastLlmCall");
                log.warn("Tenant detail: last LLM call lookup failed for schema '{}'", schema, e);
            }
            // Same source and window as the roster's spend30dUsd (rollup + live today).
            PlatformSpendService.TenantSpendTotals spend =
                    spendService
                            .spendForTenants(
                                    List.of(t), PlatformSpendService.resolveRange(null, null))
                            .get(t.getId());
            if (spend == null) {
                unavailable.add("spend30d");
            } else {
                spend30d = spend.allProvidersUsd();
            }
        }

        Instant lastSeen = null;
        boolean lastSeenKnown = false;
        try {
            lastSeen = appUserRepository.findLatestSeenForTenant(t.getId());
            lastSeenKnown = true;
        } catch (RuntimeException e) {
            unavailable.add("lastSeen");
            log.warn("Tenant detail: last-seen lookup failed for tenant {}", t.getId(), e);
        }
        Instant lastActivity =
                (lastSeenKnown || lastCallKnown) ? latest(lastSeen, lastLlmCall) : null;

        // The hosted-pool counter is keyed by schema name (the TenantContext tenantId
        // that FreeTierChatClientService reserves against), not by the Tenant UUID.
        Quota quota = null;
        try {
            FreeTierUsageLimiterService.QuotaSnapshot s =
                    usageLimiter.quotaSnapshot(schema, t.getPlan());
            quota = new Quota(s.tokensUsedMonth(), s.tokensMonthlyLimit(), t.isUsePlatformLlm());
        } catch (RuntimeException e) {
            unavailable.add("quota");
            log.warn("Tenant detail: quota snapshot failed for schema '{}'", schema, e);
        }

        return new TenantDetail(
                t.getId(),
                t.getName(),
                schema,
                t.getStatus().name(),
                t.getPlan(),
                t.getAccountType() == null ? null : t.getAccountType().name(),
                t.getDataResidencyRegion(),
                t.getCreatedAt(),
                t.isEmergencyStopped(),
                t.getEmergencyStopReason(),
                t.getEmergencyStoppedAt(),
                accessState(t),
                t.getSubscriptionStatus(),
                t.isPastDue(),
                t.getCompPlanExpiresAt(),
                users,
                agents,
                workflows,
                quota,
                lastLlmCall,
                lastCallKnown,
                unavailable,
                lastActivity,
                spend30d);
    }

    static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    /**
     * Mirrors {@code TenantStatusGuardImpl#computeUsable} (the per-tenant half; the platform kill
     * switch is separate).
     */
    static String accessState(Tenant t) {
        if (t.getStatus() != TenantStatus.ACTIVE) {
            return "BLOCKED_STATUS";
        }
        return t.isEmergencyStopped() ? "BLOCKED_EMERGENCY_STOP" : "ALLOWED";
    }

    private static Long metric(String name, List<String> unavailable, Supplier<Long> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            unavailable.add(name);
            log.warn("Tenant detail: {} lookup failed", name, e);
            return null;
        }
    }

    /** Binds the TARGET tenant schema for the call and restores the caller's context afterwards. */
    private <T> T asTenant(String schema, Supplier<T> work) {
        Optional<TenantContext> previous = TenantContext.current();
        try {
            TenantContext.set(TenantContext.of(schema, SYSTEM_ACTOR, Set.of()));
            return work.get();
        } finally {
            if (previous.isPresent()) {
                TenantContext.set(previous.get());
            } else {
                TenantContext.clear();
            }
        }
    }

    public record TenantRef(UUID tenantId, String name, String status, boolean emergencyStopped) {}

    public record Quota(long tokensUsedMonth, long tokensMonthlyLimit, boolean usesPlatformLlm) {}

    /**
     * Null count = not determinable (listed in {@code unavailable}) or not applicable for a
     * non-ACTIVE tenant. {@code lastActivityAt} = the later of any member's {@code
     * app_user.last_seen_at} and the last LLM call; {@code spend30dUsd} = all-provider estimated
     * cost over the trailing 30 UTC days (ACTIVE tenants only).
     */
    public record TenantDetail(
            UUID tenantId,
            String name,
            String schemaName,
            String status,
            TenantPlan plan,
            String accountType,
            DataResidencyRegion dataResidencyRegion,
            Instant createdAt,
            boolean emergencyStopped,
            String emergencyStopReason,
            Instant emergencyStoppedAt,
            String accessState,
            String subscriptionStatus,
            boolean pastDue,
            Instant compPlanExpiresAt,
            Long userCount,
            Long agentCount,
            Long workflowCount,
            Quota quota,
            Instant lastLlmCallAt,
            boolean lastLlmCallKnown,
            List<String> unavailable,
            Instant lastActivityAt,
            BigDecimal spend30dUsd) {}
}
