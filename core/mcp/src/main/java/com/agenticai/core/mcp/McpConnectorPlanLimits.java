package com.agenticai.core.mcp;

import com.agenticai.core.security.TenantPlan;
import java.util.Optional;

/**
 * Self-contained, code-defined MCP connector caps per {@link TenantPlan} -
 * fixing the 2026-07-27 billing audit finding that {@code core/mcp} had ZERO
 * plan-awareness (a FREE tenant could register/activate as many MCP
 * connectors as an UNLIMITED tenant).
 *
 * <p>Deliberately NOT a new {@code plan_limits} column/field (that table is
 * owned by {@code core/tenancy}'s {@code PlanLimitsService}, out of this
 * module's scope for this change) - these are fixed, code-defined defaults
 * local to {@code core/mcp}, same "sensible default per plan, own config in
 * the module that enforces it" approach the other plan-gating work this
 * session uses elsewhere. If these ever need to be admin-editable at
 * runtime, the natural next step is a {@code max_mcp_connectors} column
 * alongside {@link com.agenticai.core.security.TenantLimits#maxAgents}, not
 * more constants here.
 *
 * <p>See {@link McpRegistryService#register} for where these are enforced:
 * a standing ceiling re-checked on every new registration (not just once),
 * so removing a connector always re-opens a slot under the same cap - same
 * posture as {@code AgentService}'s {@code maxAgents} gate.
 */
final class McpConnectorPlanLimits {

    /**
     * FREE: built-in/curated connectors only (see {@link #allowsCustomServers}),
     * capped small - this tier routes through the platform-hosted pool and
     * isn't meant to run its own external tool infrastructure.
     */
    private static final int FREE_MAX_CONNECTORS = 2;

    /** PREMIUM: generous cap, custom third-party servers unlocked. */
    private static final int PREMIUM_MAX_CONNECTORS = 10;

    // UNLIMITED: no plan-level cap - Optional.empty(), same "no ceiling"
    // convention TenantLimits#maxAgents/#maxWorkflows/#maxKbCollections
    // already use for this tier.

    private McpConnectorPlanLimits() {
    }

    /** The standing ceiling on total registered MCP connectors for this plan, or empty for "no cap." */
    static Optional<Integer> maxConnectors(TenantPlan plan) {
        return switch (plan) {
            case FREE -> Optional.of(FREE_MAX_CONNECTORS);
            case PREMIUM -> Optional.of(PREMIUM_MAX_CONNECTORS);
            case UNLIMITED -> Optional.empty();
        };
    }

    /**
     * Whether this plan may register a connector outside the curated
     * {@link McpCatalogService} catalog (an arbitrary custom command/URL, or
     * one found via {@code McpRegistryController#search}'s public-registry
     * browse). FREE is restricted to catalog entries only - see {@link
     * McpRegistryService#register} for how catalog membership is checked
     * (by {@code name}, the only "is this built-in" signal the registry
     * model currently exposes - there is no persisted
     * catalog-vs-custom/source column on {@code McpServerRegistration}).
     */
    static boolean allowsCustomServers(TenantPlan plan) {
        return plan != TenantPlan.FREE;
    }
}
