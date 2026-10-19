package com.agenticai.core.platform;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.agents.quota.FreeTierUsageLimiterService;
import com.agenticai.core.chat.ModelCapabilitySummaryGenerationService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.FeatureFlagService;
import com.agenticai.core.tenancy.PlatformFeatureBudgetService;
import com.agenticai.core.tenancy.PlatformKillSwitchStatus;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.SiteWidePromoResult;
import com.agenticai.core.tenancy.SiteWidePromoService;
import com.agenticai.core.tenancy.TenantEmergencyControlService;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Ecosystem Maintenance API for the platform super-admin - cross-tenant, never scoped to a
 * single tenant's own data. Gated entirely by {@code SecurityConfig}'s {@code /api/platform/** ->
 * ROLE_platform-admin} rule (see that class - already done, not touched here); there is
 * deliberately no separate authorization check inside this class itself, same posture as {@code
 * UserAdminController}.
 *
 * <p>Mirrors {@code SystemStatusController}'s exact style: constructor injection with final fields,
 * a single constructor (no {@code @Autowired} needed), bare DTOs returned directly (not {@code
 * ResponseEntity}-wrapped). The hosted-LLM pool, budget and quota endpoints are inherited from
 * {@link PlatformLlmControlEndpoints} (same base path, same operation ids).
 *
 * <p>{@code GET /api/platform/health} is deliberately NOT one of this controller's methods - {@code
 * com.agenticai.core.common.systemstatus.SystemStatusController} (and the {@code
 * SystemStatusResponse} it produces) live in the {@code app} module, which depends on this {@code
 * platform} module, not the other way around; a compile-time dependency from {@code platform} back
 * onto {@code app} would be a circular Maven dependency. That endpoint is instead a thin
 * passthrough controller living alongside {@code SystemStatusController} itself (same package, same
 * module) - see that class's sibling for the actual {@code /api/platform/health} mapping, which
 * delegates to the exact same instance {@code SystemStatusController} uses, with zero duplicated
 * health-check logic.
 *
 * <p>{@code @Profile("security")}, same posture as {@code UserAdminController}: this controller
 * injects {@link PlatformTenantMetricsService}, which is itself {@code @Profile("security")}-gated
 * (it depends on {@code KeycloakAdminClient}) - without this annotation here too, Spring would fail
 * to wire this controller whenever the security profile isn't active. In practice {@code
 * /api/platform/**} is unreachable without the security profile anyway (see {@code
 * SecurityConfig}'s {@code ROLE_platform-admin} rule, which only exists once that profile's filter
 * chain is active).
 */
@RestController
@RequestMapping("/api/platform")
@Profile("security")
public class PlatformController extends PlatformLlmControlEndpoints {

    private final TranslationJobSettingsService translationJobSettingsService;
    private final PlatformTenantMetricsService platformTenantMetricsService;
    private final TranslationRunService translationRunService;
    private final ProviderModelCatalogService providerModelCatalogService;
    // Backfills provider_model_catalog.capability_summary - lives in
    // core/chat, not core/providers, see that class's javadoc.
    private final ModelCapabilitySummaryGenerationService modelCapabilitySummaryGenerationService;
    private final PlatformTenantPlanService platformTenantPlanService;
    private final PlatformObservabilityService platformObservabilityService;
    private final TenantEmergencyControlService tenantEmergencyControlService;
    // Master Roadmap 2026 item #13 (A/B testing & feature flags, §B).
    private final FeatureFlagService featureFlagService;
    // Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G).
    private final PlatformTenantDataResidencyService platformTenantDataResidencyService;
    private final SiteWidePromoService siteWidePromoService;
    private final TenantRepository tenantRepository;

    public PlatformController(
            PlatformLlmTaskService platformLlmTaskService,
            TranslationJobSettingsService translationJobSettingsService,
            PlatformTenantMetricsService platformTenantMetricsService,
            TranslationRunService translationRunService,
            PlatformHostedLlmProviderService platformHostedLlmProviderService,
            ProviderModelCatalogService providerModelCatalogService,
            ModelCapabilitySummaryGenerationService modelCapabilitySummaryGenerationService,
            PlatformTenantPlanService platformTenantPlanService,
            PlatformObservabilityService platformObservabilityService,
            TenantEmergencyControlService tenantEmergencyControlService,
            PlatformSettingsService platformSettingsService,
            FeatureFlagService featureFlagService,
            PlatformTenantDataResidencyService platformTenantDataResidencyService,
            PerUserBudgetService perUserBudgetService,
            PlatformFeatureBudgetService smartAiBudgetService,
            FreeTierUsageLimiterService freeTierUsageLimiterService,
            SiteWidePromoService siteWidePromoService,
            TenantRepository tenantRepository) {
        super(
                platformLlmTaskService,
                platformHostedLlmProviderService,
                platformSettingsService,
                perUserBudgetService,
                smartAiBudgetService,
                freeTierUsageLimiterService);
        this.translationJobSettingsService = translationJobSettingsService;
        this.platformTenantMetricsService = platformTenantMetricsService;
        this.translationRunService = translationRunService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.modelCapabilitySummaryGenerationService = modelCapabilitySummaryGenerationService;
        this.platformTenantPlanService = platformTenantPlanService;
        this.platformObservabilityService = platformObservabilityService;
        this.tenantEmergencyControlService = tenantEmergencyControlService;
        this.featureFlagService = featureFlagService;
        this.platformTenantDataResidencyService = platformTenantDataResidencyService;
        this.siteWidePromoService = siteWidePromoService;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Master Roadmap 2026 item #13 (A/B testing & feature flags, §B) - list/
     * create-or-update/delete a flag definition, plus a per-flag assignment list so a platform
     * admin can see which tenants have actually been bucketed into which variant. Same bare-DTO,
     * {@code TenantContext}- sourced {@code updatedBy} pattern {@link #updateSetting} above already
     * uses. No separate "evaluate" endpoint here deliberately - evaluation happens inside {@code
     * AgentChatService}'s own request path (see {@link FeatureFlagService#evaluateActiveFlags}),
     * not via a call this admin surface would make.
     */
    @GetMapping("/feature-flags")
    public List<FeatureFlagResponse> listFeatureFlags() {
        return featureFlagService.listFlags().stream().map(FeatureFlagResponse::from).toList();
    }

    @PutMapping("/feature-flags/{key}")
    public FeatureFlagResponse upsertFeatureFlag(
            @PathVariable String key, @RequestBody FeatureFlagUpsertRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return FeatureFlagResponse.from(
                featureFlagService.createOrUpdate(
                        key,
                        request.description(),
                        request.enabled(),
                        request.rolloutPercentage(),
                        updatedBy));
    }

    @DeleteMapping("/feature-flags/{key}")
    public void deleteFeatureFlag(@PathVariable String key) {
        featureFlagService.delete(key);
    }

    @GetMapping("/feature-flags/{key}/assignments")
    public List<ExperimentAssignmentResponse> listFeatureFlagAssignments(@PathVariable String key) {
        return featureFlagService.assignmentsForFlag(key).stream()
                .map(ExperimentAssignmentResponse::from)
                .toList();
    }

    /**
     * Direct feedback: "i want a central control in admin platform maintenance to set all these
     * values in UI" - triggered by SignupRateLimiter's hardcoded rate-limit knobs specifically, but
     * built generic (see {@link PlatformSettingsService}'s javadoc) so any future hardcoded
     * operational value can be moved here with just a migration row, no new endpoint. List, not a
     * single get-by-key, since the admin UI's whole point is showing every tunable value in one
     * place.
     */
    @GetMapping("/settings")
    public List<PlatformSettingResponse> listSettings() {
        return platformSettingsService.listAll().stream()
                .map(PlatformSettingResponse::from)
                .toList();
    }

    @PutMapping("/settings/{key}")
    public PlatformSettingResponse updateSetting(
            @PathVariable String key, @RequestBody PlatformSettingUpdateRequest request) {
        if (com.agenticai.core.platform.ops.PlatformBroadcastService.isReservedKey(key)) {
            // Maintenance mode 503s every tenant and must stay behind X-Master-Key; the banner has
            // validation.
            throw new IllegalArgumentException(
                    "This setting is managed by PUT /api/platform/maintenance or"
                            + " /api/platform/announcement.");
        }
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return PlatformSettingResponse.from(
                platformSettingsService.update(key, request.value(), updatedBy));
    }

    @GetMapping("/translation-job")
    public TranslationJobSettingsResponse getTranslationJob() {
        return TranslationJobSettingsResponse.from(translationJobSettingsService.get());
    }

    @PutMapping("/translation-job")
    public TranslationJobSettingsResponse updateTranslationJob(
            @RequestBody TranslationJobSettingsUpdateRequest request) {
        // Same caller-identity source UserAdminController's actorId() uses -
        // the validated JWT's subject, already populated into TenantContext
        // by TenantContextFilter before this controller ever runs.
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return TranslationJobSettingsResponse.from(
                translationJobSettingsService.update(
                        request.cronScheduleDisplay(),
                        request.providerName(),
                        request.model(),
                        request.workflowPathHint(),
                        request.notes(),
                        updatedBy));
    }

    @GetMapping("/tenants")
    public List<PlatformTenantSummary> listTenants() {
        return platformTenantMetricsService.listTenantSummaries();
    }

    /**
     * Registered tenants vs anonymous {@code /try} sandboxes, as separate figures. {@code GET
     * /tenants} above lists registered tenants only; a separate path (not a changed response shape)
     * so an older web-admin keeps working against a newer core and vice versa.
     */
    @GetMapping("/tenant-counts")
    public PlatformTenantCounts tenantCounts() {
        return platformTenantMetricsService.tenantCounts();
    }

    /**
     * Subscription Plan management page's one write action - a super-admin directly setting a
     * tenant's {@link com.agenticai.core.security.TenantPlan}. See {@link
     * PlatformTenantPlanService}'s javadoc for exactly what this does and doesn't do (never touches
     * the Razorpay subscription row).
     */
    @PutMapping("/tenants/{tenantId}/plan")
    public PlatformTenantSummary changeTenantPlan(
            @PathVariable java.util.UUID tenantId,
            @RequestBody PlatformTenantPlanUpdateRequest request) {
        return platformTenantPlanService.changePlan(tenantId, request.plan());
    }

    /**
     * Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - a platform admin declaring a
     * tenant's intended data-residency jurisdiction. See {@link
     * PlatformTenantDataResidencyService}'s javadoc for exactly what this does and doesn't do
     * (informational only, no actual data routing/migration) - same bare-summary shape as {@link
     * #changeTenantPlan} right above.
     */
    @PutMapping("/tenants/{tenantId}/data-residency")
    public PlatformTenantSummary changeDataResidencyRegion(
            @PathVariable java.util.UUID tenantId,
            @RequestBody PlatformTenantDataResidencyUpdateRequest request) {
        return platformTenantDataResidencyService.changeDataResidencyRegion(
                tenantId, request.region());
    }

    /**
     * The real on-demand action (#423) - dispatches the GitHub Actions translation workflow right
     * now instead of waiting for its weekly cron. See {@link TranslationRunService}'s javadoc for
     * exactly what this does and doesn't do (never calls an LLM directly from this JVM). Always
     * returns 200 with the updated settings row, even on failure/ not-configured - the outcome is
     * carried in {@code lastTriggerStatus}/ {@code lastTriggerMessage}, not an HTTP error status,
     * since "the trigger request was handled" and "the translation job itself succeeded" are
     * different things and this call only promises the former.
     */
    @PostMapping("/translation-job/run-now")
    public TranslationJobSettingsResponse runTranslationJobNow() {
        String triggeredBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return TranslationJobSettingsResponse.from(translationRunService.triggerNow(triggeredBy));
    }

    /**
     * "Write a pipeline or job like the translator to do this" - direct feedback about the model
     * catalog specifically. {@link ProviderModelCatalogService#scheduledRefresh()} already does the
     * pipeline part (daily at 03:00 UTC); this is that same job's manual "run now" trigger, same
     * shape as {@link #runTranslationJobNow()} right above - an admin doesn't have to wait for the
     * next 03:00 UTC window after, say, adding a new model to {@code SEED_MODELS} by hand, or if
     * OpenRouter's live catalog changed and the cached copy looks stale. Synchronous and fast
     * enough not to need the translation job's fire-and-forget GitHub Actions dispatch pattern -
     * this only makes a handful of REST calls (one per provider, OpenRouter's the only live one)
     * and writes straight to Postgres, no external CI system involved.
     *
     * <p>Also runs {@link ModelCapabilitySummaryGenerationService #generateMissingSummaries()}
     * right after the refresh - the same "no new endpoint" trigger this catalog's
     * capability-summary generation job uses (its other trigger being its own daily cron, five
     * minutes after {@code ProviderModelCatalogService#scheduledRefresh()}'s). An admin who just
     * refreshed the catalog by hand gets real summaries for any newly-added models immediately
     * rather than waiting for that job's next 03:05 UTC run.
     */
    @PostMapping("/model-catalog/refresh-now")
    public ModelCatalogRefreshResult refreshModelCatalogNow() {
        providerModelCatalogService.refreshAll();
        modelCapabilitySummaryGenerationService.generateMissingSummaries();
        return new ModelCatalogRefreshResult(Instant.now());
    }

    /**
     * Bare acknowledgement - the catalog itself is read back via the existing per-provider {@code
     * GET /api/providers/{provider}/models}.
     */
    public record ModelCatalogRefreshResult(Instant refreshedAt) {}

    /**
     * The Platform Observability page's three read endpoints - direct feedback: "an page in admin
     * platform where I can see the entire tickets, errors, tenants, execution, platform usage, no
     * of calls, llm used and all information and activities done by client." {@code /tenants} above
     * already covers tenants/execution (agent+workflow counts); these three cover the rest. See
     * {@link PlatformObservabilityService}'s own javadoc for the exact scope boundary on
     * "execution" and "platform usage" - stated there rather than silently narrowed.
     */
    @GetMapping("/issues")
    public ResponseEntity<List<PlatformIssueSummary>> listIssues(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return PlatformListPaging.window(platformObservabilityService.listIssues(), limit, offset);
    }

    /**
     * Exact OPEN-issue count across registered active tenants, for the overview tile - {@code
     * /issues} above is a capped recent list and must not be counted client-side. See {@link
     * PlatformObservabilityService#openIssueCount}.
     */
    @GetMapping("/issues/open-count")
    public PlatformObservabilityService.OpenIssueCount openIssueCount() {
        return platformObservabilityService.openIssueCount();
    }

    @GetMapping("/llm-usage")
    public List<PlatformTenantLlmUsageSummary> llmUsageByTenant() {
        return platformObservabilityService.llmUsageByTenant();
    }

    /**
     * #973 - "consider the price, charges to get the open source llm from open router or any other
     * platform." Distinct from {@code /llm-usage} above: that endpoint blends every provider a
     * tenant has ever used (including their own BYOK keys); this one isolates just the {@code
     * PLATFORM_HOSTED} pool - the platform's own money being spent to serve FREE-tier traffic - as
     * one fleet-wide total plus a per-tenant breakdown of who is driving that spend. See {@link
     * PlatformHostedLlmCostSummary}'s javadoc for the full rationale.
     */
    @GetMapping("/hosted-llm-cost")
    public PlatformHostedLlmCostSummary hostedLlmCostSummary() {
        return platformObservabilityService.platformHostedLlmCostSummary();
    }

    /**
     * Cost-dashboard trend line: day-bucketed {@code PLATFORM_HOSTED}-only totals across every
     * active tenant, for the trailing {@code days} days (default 30, capped at 90 to bound the
     * cross-tenant sweep's work).
     */
    @GetMapping("/hosted-llm-cost/by-day")
    public List<PlatformHostedLlmCostDay> hostedLlmCostByDay(
            @RequestParam(defaultValue = "30") int days) {
        return platformObservabilityService.platformHostedLlmCostByDay(
                Math.min(Math.max(days, 1), 90));
    }

    /**
     * Sets (or, with a null body value, clears) the admin-configured all-time budget line for the
     * {@code PLATFORM_HOSTED} pool - the "remaining balance" half of the cost dashboard. See {@link
     * PlatformHostedLlmCostSummary}'s javadoc for how the remaining balance is derived.
     */
    @PutMapping("/hosted-llm-cost/budget")
    public PlatformHostedLlmCostSummary updateHostedLlmBudget(
            @RequestBody PlatformHostedLlmBudgetUpdateRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return platformObservabilityService.updateHostedLlmBudget(request.budgetUsd(), updatedBy);
    }

    @GetMapping("/activity")
    public ResponseEntity<List<PlatformActivityEntry>> recentActivity(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return PlatformListPaging.window(platformObservabilityService.recentActivity(), limit, offset);
    }

    /**
     * Platform-admin alert threshold follow-up (#956) - fleet-wide lifetime
     * fallback-trigger/fallback-failure totals plus a per-tenant breakdown, compared against the
     * configured threshold. See {@link PlatformObservabilityService#computeFallbackAlertStatus()}'s
     * javadoc for exactly what "lifetime" means here (a deliberately simple v1, not a windowed-rate
     * engine).
     */
    @GetMapping("/alerts/fallback-status")
    public PlatformFallbackAlertStatus getFallbackAlertStatus() {
        return platformObservabilityService.computeFallbackAlertStatus();
    }

    @PutMapping("/alerts/fallback-threshold")
    public PlatformFallbackAlertStatus updateFallbackAlertThreshold(
            @RequestBody UpdateFallbackAlertThresholdRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return platformObservabilityService.updateFallbackFailureThreshold(
                request.threshold(), updatedBy);
    }

    /**
     * "It should also intelligently list all available models from the provider based on their API
     * specifications" (2026-07-26 feedback) - live probe for the web-admin add/edit form's model
     * dropdown when the row is a raw {@code baseUrl} (Groq, Together AI, Cerebras, ...), not one of
     * the {@link PlatformHostedLlmProviderService#HOSTED_POOL_COMPATIBLE_PROVIDERS} where {@code
     * GET /api/providers/{provider}/models} (the same catalog the tenant BYOK model picker uses) is
     * instant and needs no key at all. Never throws - {@link
     * PlatformHostedLlmProviderService#discoverModels} returns an empty list on any failure so the
     * admin form degrades to manual model entry rather than erroring the screen.
     */
    @PostMapping("/hosted-llm-providers/discover-models")
    public DiscoverHostedModelsResponse discoverHostedModels(
            @RequestBody DiscoverHostedModelsRequest request) {
        List<String> models =
                platformHostedLlmProviderService.discoverModels(
                        request.baseUrl(), request.apiKey(), request.completionsPath());
        return new DiscoverHostedModelsResponse(models);
    }

    public record DiscoverHostedModelsRequest(
            String baseUrl, String apiKey, String completionsPath) {}

    public record DiscoverHostedModelsResponse(List<String> models) {}

    /**
     * #815 Governance follow-up (Fail-safe: platform + per-tenant kill switch) - the operable
     * surface for {@link TenantEmergencyControlService}. Both the platform-wide and per-tenant
     * actions are enforced by {@code TenantStatusGuardImpl#isUsable} (JWT path) and {@code
     * AgentChatService#chat} (omnichannel-webhook path) - see those classes' javadoc. {@code
     * updatedBy} is the same validated-JWT-subject source every other write action in this
     * controller already uses (e.g. {@link #updateTranslationJob}).
     */
    @GetMapping("/kill-switch")
    public PlatformKillSwitchStatus getKillSwitchStatus() {
        return tenantEmergencyControlService.getStatus();
    }

    @PostMapping("/kill-switch/stop")
    public PlatformKillSwitchStatus stopPlatform(@RequestBody EmergencyStopRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        tenantEmergencyControlService.stopPlatform(request.reason(), updatedBy);
        return tenantEmergencyControlService.getStatus();
    }

    @PostMapping("/kill-switch/resume")
    public PlatformKillSwitchStatus resumePlatform() {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        tenantEmergencyControlService.resumePlatform(updatedBy);
        return tenantEmergencyControlService.getStatus();
    }

    /**
     * Per-tenant counterpart of {@link #stopPlatform} - stops ONE tenant (see {@link
     * com.agenticai.core.tenancy.Tenant#emergencyStop}) rather than the whole platform. Returns
     * nothing; the tenant roster UI already has a dedicated {@code GET /api/platform/tenants}
     * refresh it can call to pick up the new {@code emergencyStopped} flag on {@link
     * PlatformTenantSummary}.
     */
    @PostMapping("/tenants/{tenantId}/emergency-stop")
    public void emergencyStopTenant(
            @PathVariable UUID tenantId, @RequestBody EmergencyStopRequest request) {
        String updatedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        tenantEmergencyControlService.stopTenant(tenantId, request.reason(), updatedBy);
    }

    @PostMapping("/tenants/{tenantId}/emergency-resume")
    public void emergencyResumeTenant(@PathVariable UUID tenantId) {
        tenantEmergencyControlService.resumeTenant(tenantId);
    }

    /**
     * 2026-08-14 site-wide promo - one-time, hand-triggered bulk grant of {@code UNLIMITED} to
     * every {@code ACTIVE} tenant for {@code request.durationDays()}, auto-reverting via the same
     * hourly {@code CompGrantExpiryReverter} every other comp grant already uses (see {@code
     * SiteWidePromoService}'s javadoc for why this reuses that mechanism instead of a new one, and
     * why the grant and any email send are two separate calls rather than one). Idempotent:
     * re-calling this skips any tenant already on {@code UNLIMITED} rather than stacking a second
     * grant on top.
     *
     * <p>{@code request.sendEmail()} defaults false - sending mail to every registered tenant's
     * admin is deliberately a second, explicit opt-in on this same call, not bundled silently into
     * "grant the plan."
     */
    @PostMapping("/promos/unlimited-14d")
    public SiteWidePromoResult grantSiteWideUnlimitedPromo(
            @RequestBody SiteWidePromoRequest request) {
        String triggeredBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        SiteWidePromoResult result =
                siteWidePromoService.grantUnlimitedToAllActiveTenants(
                        request.durationDays(), request.reason(), triggeredBy);
        if (request.sendEmail()) {
            siteWidePromoService.sendPromoEmails(result.grantedTenants(), result.expiresAt());
        }
        return result;
    }

    /**
     * Support-desk action for a tenant stuck against {@link FreeTierUsageLimiterService}'s monthly
     * token cap (e.g. hit legitimately, or via the {@code FreeTierTokenTrackingAdvisor}
     * client-disconnect reservation leak fixed 2026-08-09). Per-tenant, not global - see {@link
     * #getFreeTierQuota}'s javadoc for the sibling endpoint that changes the LIMIT itself rather
     * than just this tenant's current usage. Deletes the window counter outright rather than
     * partially crediting back an estimate - same "unambiguous, instantly verifiable" reasoning as
     * {@link FreeTierUsageLimiterService#resetTenantUsage}'s own javadoc. Void/no-body response,
     * same shape as {@link #emergencyResumeTenant} right above - there is no persistent flag on
     * {@link PlatformTenantSummary} for this to reflect back.
     */
    @PostMapping("/tenants/{tenantId}/usage-quota/reset")
    public void resetTenantUsageQuota(@PathVariable UUID tenantId) {
        // The hosted-pool counter is keyed by the request's TenantContext id, which is the
        // tenant's schema name (Keycloak's tenant_id attribute) - NOT the control_plane UUID
        // in this URL. Resetting by UUID deleted a key nothing ever wrote or read.
        String counterKey =
                tenantRepository
                        .findById(tenantId)
                        .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId))
                        .getSchemaName();
        freeTierUsageLimiterService.resetTenantUsage(counterKey);
    }
}
