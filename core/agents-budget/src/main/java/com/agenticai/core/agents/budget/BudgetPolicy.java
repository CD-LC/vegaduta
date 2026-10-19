package com.agenticai.core.agents.budget;

import com.agenticai.core.providers.ProviderName;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;

/**
 * An agent's spend controls: the optional per-run cap and the economy-model cost routing. Embedded
 * in {@code Agent}; the columns keep their names. A null run cap means "use the platform cap".
 */
@Embeddable
public class BudgetPolicy {

    // Per-agent run cap (migration V421). NULLABLE on purpose: null means
    // "use the platform cap app.guardrails.run-budget.usd", the exact
    // pre-existing behavior. Can only lower the platform cap - see
    // RunBudgetCaps. Deliberately NOT part of AgentConfigSnapshot.
    @Column(name = "run_budget_usd", precision = 10, scale = 4)
    private java.math.BigDecimal runBudgetUsd;

    // #1077 fast-follow to the item above - the "routing" half of Master
    // Roadmap #18 that #1076 deliberately left for a follow-up (that item's
    // own scope was "the semantic caching half"). RouteLLM/NotDiamond-style:
    // route each turn to a cheaper/faster "economy" model instead of this
    // agent's normal (primary) model when QueryComplexityClassifier judges
    // the turn simple - same off-by-default, explicit-opt-in shape as
    // responseCachingEnabled right above, for the identical reason: whether
    // a downgraded model still gives an acceptable answer depends on what
    // the agent is FOR, which only the tenant who configured it knows.
    // economyProviderName/economyModel are both nullable independent of the
    // flag (mirrors fallbackProviderName/fallbackModel above) - enabling
    // costRoutingEnabled with no economy model configured is simply a no-op
    // (see QueryComplexityClassifier/AgentChatService#buildChatClientFor),
    // not an error state, so a tenant can turn the flag on before deciding
    // which model to route simple turns to. Migration V99 adds all three as
    // false/null/null - no pre-existing agent behavior changes.
    @Column(name = "cost_routing_enabled", nullable = false)
    private boolean costRoutingEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "economy_provider_name", length = 32)
    private ProviderName economyProviderName;

    @Column(name = "economy_model", length = 128)
    private String economyModel;

    // Per-agent Jev sensitivity override (V334, tenant "full control" ask):
    // the minimum noul probability QueryComplexityClassifier requires before
    // downgrading THIS agent's "probably complex" turn to the economy model.
    // null = inherit (tenant jev_policy.simple_threshold, else the platform's
    // app.jev.complexity.simple-threshold default). Deliberately nullable so
    // the override is opt-in and visibly distinct from the inherited value.
    @Column(name = "cost_routing_jev_threshold")
    private Double costRoutingJevThreshold;

    /** JPA, and the "no cap, no routing" starting state. */
    protected BudgetPolicy() {}

    public static BudgetPolicy none() {
        return new BudgetPolicy();
    }

    private BudgetPolicy copy() {
        BudgetPolicy p = new BudgetPolicy();
        p.runBudgetUsd = runBudgetUsd;
        p.costRoutingEnabled = costRoutingEnabled;
        p.economyProviderName = economyProviderName;
        p.economyModel = economyModel;
        p.costRoutingJevThreshold = costRoutingJevThreshold;
        return p;
    }

    public BudgetPolicy withRunBudgetUsd(java.math.BigDecimal runBudgetUsd) {
        BudgetPolicy p = copy();
        p.runBudgetUsd = runBudgetUsd;
        return p;
    }

    /** A null economy provider clears the economy model. */
    public BudgetPolicy withCostRouting(
            boolean enabled, ProviderName economyProviderName, String economyModel, Double jevThreshold) {
        BudgetPolicy p = copy();
        p.costRoutingEnabled = enabled;
        p.economyProviderName = economyProviderName;
        p.economyModel = economyProviderName == null ? null : economyModel;
        p.costRoutingJevThreshold = jevThreshold;
        return p;
    }

    public java.math.BigDecimal getRunBudgetUsd() {
        return runBudgetUsd;
    }

    public boolean isCostRoutingEnabled() {
        return costRoutingEnabled;
    }

    public ProviderName getEconomyProviderName() {
        return economyProviderName;
    }

    public String getEconomyModel() {
        return economyModel;
    }

    public Double getCostRoutingJevThreshold() {
        return costRoutingJevThreshold;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BudgetPolicy b
                && Objects.equals(runBudgetUsd, b.runBudgetUsd)
                && costRoutingEnabled == b.costRoutingEnabled
                && economyProviderName == b.economyProviderName
                && Objects.equals(economyModel, b.economyModel)
                && Objects.equals(costRoutingJevThreshold, b.costRoutingJevThreshold);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runBudgetUsd, costRoutingEnabled, economyProviderName, economyModel, costRoutingJevThreshold);
    }
}
