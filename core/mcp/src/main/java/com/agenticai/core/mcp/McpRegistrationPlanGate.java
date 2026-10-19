package com.agenticai.core.mcp;

import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.util.StringUtils;

/**
 * Plan-awareness and catalog trust for {@link McpRegistryService#register} (2026-07-27 billing
 * audit fix, SEC-002): decides whether a save reproduces a curated catalog entry's exact launch
 * spec, enforces {@link McpConnectorPlanLimits} on a new connector and on a launch-spec change,
 * and produces the soft missing-credential warnings. Created and owned by {@link
 * McpRegistryService}; not a bean.
 */
final class McpRegistrationPlanGate {

    private final McpServerRegistrationRepository repository;
    private final TenantLimits tenantLimits;
    private final McpCatalogService catalogService;
    private final McpRegistrationEnv envCodec;

    McpRegistrationPlanGate(
            McpServerRegistrationRepository repository,
            TenantLimits tenantLimits,
            McpCatalogService catalogService,
            McpRegistrationEnv envCodec) {
        this.repository = repository;
        this.tenantLimits = tenantLimits;
        this.catalogService = catalogService;
        this.envCodec = envCodec;
    }

    /**
     * SEC-002: whether this save reproduces a curated catalog entry's exact launch spec (see {@link
     * McpCatalogService#findStrictMatch}). The env-key set checked is the one this save will
     * actually persist - on an update with a non-empty env, the stored env merged with the incoming
     * one (see {@link McpRegistrationEnv#resolveForUpdate}); with a null env, the stored env unchanged. A
     * name-only request (no command, no url) persists no launch spec at all, so only the name is
     * compared.
     */
    boolean isCatalogSourced(
            McpServerRegistrationRequest request,
            McpServerRegistration existing,
            boolean wasUpdate) {
        boolean hasCommand = StringUtils.hasText(request.command());
        boolean hasUrl = StringUtils.hasText(request.url());
        if (!hasCommand && !hasUrl) {
            return catalogService.isStrictCatalogMatch(
                    request.name(), request.type(), null, null, null, null);
        }
        java.util.Set<String> envKeys = new java.util.LinkedHashSet<>();
        Map<String, String> incoming = request.env();
        if (existing != null && wasUpdate && (incoming == null || !incoming.isEmpty())) {
            envKeys.addAll(envCodec.decryptDetailed(existing.getEncryptedEnv()).env().keySet());
        }
        if (incoming != null) {
            envKeys.addAll(incoming.keySet());
        }
        return catalogService.isStrictCatalogMatch(
                request.name(),
                request.type(),
                hasCommand ? request.command() : null,
                request.args(),
                envKeys,
                hasCommand ? null : request.url());
    }

    /**
     * 2026-07-27 billing audit fix: before this, a FREE tenant could register as many MCP
     * connectors as an UNLIMITED tenant - zero plan-awareness in this module. Enforces {@link
     * McpConnectorPlanLimits} the same way {@code AgentService#create}'s {@code maxAgents} gate
     * works: fetch the tenant's plan, count what they already have, reject with a client-actionable
     * {@link TenantLimitExceededException} (never a server error) if this new connector would push
     * them over their plan's ceiling - and, for FREE specifically, also reject a name that isn't
     * one of the curated {@link McpCatalogService} entries (no arbitrary custom command/URL, no
     * registry-browsed third-party server).
     *
     * <p>{@code tenantId} is null for callers with no HTTP request context (none exist today, but
     * this guards against ever silently skipping the check rather than NPE-ing, same caveat {@code
     * AgentService#create}'s own comment makes) - such callers simply aren't capped.
     */
    void enforceForNewConnector(
            McpServerRegistrationRequest request, boolean catalogSourced) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return;
        }

        TenantPlan plan = tenantLimits.plan(tenantId);

        Optional<Integer> maxConnectors = McpConnectorPlanLimits.maxConnectors(plan);
        if (maxConnectors.isPresent() && repository.count() >= maxConnectors.get()) {
            throw new TenantLimitExceededException(
                    "The "
                            + tenantLimits.plan(tenantId)
                            + " plan allows up to "
                            + maxConnectors.get()
                            + " MCP connectors at a time - upgrade your plan, or remove an existing"
                            + " connector, to add another.");
        }

        if (!McpConnectorPlanLimits.allowsCustomServers(plan) && !catalogSourced) {
            throw new TenantLimitExceededException(
                    "The "
                            + tenantLimits.plan(tenantId)
                            + " plan only supports connectors from the built-in MCP catalog -"
                            + " upgrade your plan to connect a custom third-party MCP server.");
        }
    }

    /**
     * The FREE-plan "catalog connectors only" rule on its own - used on the update path, where the
     * connector-count cap deliberately does not apply but a changed launch spec must still be a
     * catalog spec (SEC-002).
     */
    void enforceCustomServerGate(boolean catalogSourced) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null || catalogSourced) {
            return;
        }
        TenantPlan plan = tenantLimits.plan(tenantId);
        if (!McpConnectorPlanLimits.allowsCustomServers(plan)) {
            throw new TenantLimitExceededException(
                    "This plan ("
                            + tenantLimits.accountType(tenantId)
                            + ") only supports connectors from the built-in MCP catalog with their"
                            + " standard launch settings - upgrade your plan to connect a custom"
                            + " third-party MCP server.");
        }
    }

    /**
     * Soft validation (2026-08-05): a missing required credential never blocks registration
     * outright (some required registry vars have a registry-supplied default - {@code
     * RegistryEnvVarSpec#defaultValue} - and a catalog entry's own required list is not itself
     * hard-enforced today), but it should be visible immediately rather than discovered only via a
     * failed connect attempt minutes later.
     *
     * <p><b>Known deviation, stated plainly:</b> this only checks catalog- sourced STDIO
     * registrations, because {@link McpCatalogEntry#requiredEnvVars()} is the only required-env
     * schema this service can resolve BY NAME at register() time. A registry-browsed ("Browse
     * registry") server's {@code RegistryEnvVarSpec} list (which DOES carry required/default/
     * secret metadata) only ever exists transiently in the search response - {@link
     * McpServerRegistrationRequest} carries no field linking a registration back to the registry
     * entry it came from, and the frontend does not send one today. Wiring that through would mean
     * either persisting the spec alongside the registration or re-querying the registry by name at
     * register() time (a live network call inside a save path) - both are real, scoped follow-ups,
     * not something to half-add here. A registry-sourced (non-catalog) registration simply gets no
     * soft-validation warning at register() time; it still gets a NAMED missing-var hint later if a
     * connect attempt actually times out and the var happens to also be a catalog name match (rare)
     * - see {@code McpConnectionDiagnoser#diagnoseStdioFailure}'s own javadoc for the identical
     * limitation there.
     */
    List<String> missingRequiredEnvWarnings(
            McpServerRegistrationRequest request, boolean catalogSourced) {
        if (!catalogSourced) {
            return List.of();
        }
        Optional<McpCatalogEntry> entry = catalogService.find(request.name());
        if (entry.isEmpty()) {
            return List.of();
        }
        Map<String, String> env = request.env() == null ? Map.of() : request.env();
        List<String> missing = new ArrayList<>();
        for (String required : entry.get().requiredEnvVars()) {
            if (!env.containsKey(required) || !StringUtils.hasText(env.get(required))) {
                missing.add(
                        "Missing recommended credential '"
                                + required
                                + "' - "
                                + request.name()
                                + " may fail to connect without it.");
            }
        }
        return missing;
    }
}
