package com.agenticai.core.agents.decision;

import com.agenticai.core.providers.capability.CapabilityPolicyService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TtlCache;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The DECISION capability's routing facade - the single type the internal
 * consumers ({@code QueryComplexityClassifier}, {@code
 * AgentRelevanceSelector}) and both REST surfaces ({@code
 * DecisionCapabilityController}, the {@code /api/jev} compat shim) call.
 * Provider-agnostic by construction: it autowires every {@link DecisionModel}
 * bean and never names a vendor. Onboarding decision provider #2 = one {@code
 * DecisionModel} impl + one {@code ProviderCredentialValidator} bean + it
 * appears here automatically - this class never gets edited.
 *
 * <p><b>Active-provider selection.</b> A model earns the call when it's
 * enabled AND a key resolves for the current request; a model riding the
 * TENANT's own credential wins over one riding a platform/env key (the
 * tenant explicitly chose to pay for it). With a single registered provider
 * this reduces to exactly the pre-registry behavior.
 *
 * <p><b>Fail-open stays in the models.</b> Each {@code assess}-style method
 * delegates to the active model once - the model's own contract already
 * degrades to empty on any failure, and the consumers' heuristic fallback is
 * what an empty answer is FOR.
 *
 * <p><b>Policy is capability-level, not provider-level.</b> {@link
 * #tenantPolicy()} reads the tenant's {@code capability_policy} row for
 * {@code decision} via {@link CapabilityPolicyService}, TTL-cached per tenant
 * exactly like the per-model key caches - a policy PUT takes at most the TTL
 * to reach every pod.
 */
@Service
public class DecisionEngine {

    private final List<DecisionModel> models;
    // Nullable: slim test contexts that never declare the policy service get
    // a fully functional engine whose tenants simply can't override.
    private final CapabilityPolicyService policies;
    private final TtlCache<Optional<DecisionPolicyView>> policyCache;

    @Autowired
    public DecisionEngine(
        List<DecisionModel> models,
        ObjectProvider<CapabilityPolicyService> policies,
        @Value("${app.decision.policy-cache-ttl:30s}") Duration policyCacheTtl
    ) {
        this.models = models == null ? List.of() : List.copyOf(models);
        this.policies = policies.getIfAvailable();
        this.policyCache = new TtlCache<>("decision-policy", policyCacheTtl, 256);
    }

    /** Test seam: explicit models + policy service, no Spring. */
    public DecisionEngine(List<DecisionModel> models, CapabilityPolicyService policies, Duration policyCacheTtl) {
        this.models = models == null ? List.of() : List.copyOf(models);
        this.policies = policies;
        this.policyCache = new TtlCache<>("decision-policy", policyCacheTtl, 256);
    }

    /**
     * The provider to answer with: among models that are enabled and have a
     * resolved key, a TENANT-sourced key wins (tenant's own account, their
     * explicit choice), else the first available model. Empty when nothing
     * can serve - the universal "fall back to the heuristic" signal.
     */
    public Optional<DecisionModel> active() {
        DecisionModel firstAvailable = null;
        for (DecisionModel model : models) {
            if (!model.isEnabled() || !model.isAvailable()) {
                continue;
            }
            if (model.keySource().orElse(null) == DecisionModel.KeySource.TENANT) {
                return Optional.of(model);
            }
            if (firstAvailable == null) {
                firstAvailable = model;
            }
        }
        return Optional.ofNullable(firstAvailable);
    }

    /** True when ANY registered model is enabled - the status surface distinguishes this from "is a key configured." */
    public boolean isEnabled() {
        return models.stream().anyMatch(DecisionModel::isEnabled);
    }

    /** True when some model can serve this request right now. */
    public boolean isAvailable() {
        return active().isPresent();
    }

    /** Which key source the active model resolves to - "the decision capability is running on YOUR key" vs the platform's. */
    public Optional<DecisionModel.KeySource> keySource() {
        return active().flatMap(DecisionModel::keySource);
    }

    /**
     * The CURRENT tenant's decision policy view - raw nullable overrides, NOT
     * resolved values; each consumer combines a field with its own platform
     * flag. Empty without a tenant context (tenant-less callers have no
     * policy row to read, and the tenant-schema table doesn't exist on the
     * public connection). TTL-cached per tenant like the models' key caches.
     */
    public Optional<DecisionPolicyView> tenantPolicy() {
        if (policies == null) {
            return Optional.empty();
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return Optional.empty();
        }
        return policyCache.get(TtlCache.key("tenant", tenantId, "decision-policy"), () ->
            policies.currentOverrides(DecisionPolicyView.CAPABILITY).map(DecisionPolicyView::from));
    }

    public Optional<Double> noul(String state, String instructions) {
        return active().flatMap(m -> m.noul(state, instructions));
    }

    public Map<String, Double> nouls(String state, Map<String, String> instructionsByKey) {
        return active().map(m -> m.nouls(state, instructionsByKey)).orElse(Map.of());
    }

    public Optional<DecisionModel.Choice> choose(String state, String instructions, Map<String, String> criteria) {
        return active().flatMap(m -> m.choose(state, instructions, criteria));
    }

    public Optional<DecisionModel.Score> score(String state, String instructions, List<String> levels) {
        return active().flatMap(m -> m.score(state, instructions, levels));
    }

    /** Fail-visible playground pass-through to the active model - empty when no provider can serve at all. */
    public Optional<DecisionModel.PlaygroundOutcome> playground(String state, Map<String, JsonNode> questions) {
        return active().flatMap(m -> m.playground(state, questions));
    }
}
