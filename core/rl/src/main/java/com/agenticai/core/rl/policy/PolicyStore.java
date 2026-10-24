package com.agenticai.core.rl.policy;

import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.BanditAlgorithm;
import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.repo.AgentPolicyRepository;
import com.agenticai.core.rl.repo.PolicyArmRepository;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingSource;
import com.agenticai.core.settings.SettingValue;
import com.agenticai.core.settings.SettingsResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD for {@link AgentPolicy}/{@link PolicyArm} plus default-arm seeding per
 * {@link PolicyDimension}. A policy starts DISABLED (nothing explores until an
 * admin turns the dimension on), with a modest epsilon exploration floor ({@code rl.policy.default-exploration-rate}, 0.1).
 *
 * <p>Seeded arm sets:
 * <ul>
 *   <li>REASONING_DEPTH - FAST / CAREFUL / RIGOROUS ({@code rl.policy.reasoning-depth-arms}).</li>
 *   <li>GEN_PARAMS - PRECISE / BALANCED / CREATIVE temperature/topP presets ({@code rl.policy.gen-param-presets}).</li>
 *   <li>MODEL_ROUTE - one arm per configured {@code rl.policy.model-route-arms}
 *       entry (none by default).</li>
 *   <li>TOOL_SUBSET - a single ALL arm (the safe default: offer the agent's
 *       normal toolset). Narrower read-only subsets can be added later.</li>
 *   <li>PROMPT_VARIANT - a single DEFAULT arm; concrete prompt-version arms are
 *       added by the orchestrator, never invented here.</li>
 * </ul>
 */
@Service
public class PolicyStore {

    private static final Logger log = LoggerFactory.getLogger(PolicyStore.class);

    private final AgentPolicyRepository policyRepository;
    private final PolicyArmRepository armRepository;
    private final RlPolicyProperties properties;

    public PolicyStore(AgentPolicyRepository policyRepository, PolicyArmRepository armRepository, RlPolicyProperties properties) {
        this.policyRepository = policyRepository;
        this.armRepository = armRepository;
        this.properties = properties;
    }

    private SettingsResolver settings;

    /**
     * The settings catalog ({@code rl.policy.defaultExplorationRate}): a workspace's starting exploration rate for a
     * new policy replaces the property value. Without it the property applies.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettings(SettingsResolver settings) {
        this.settings = settings;
    }

    /** The rate a new policy starts at: the workspace's override when it set one, else the configured default. */
    private BigDecimal startingExplorationRate() {
        if (settings != null) {
            try {
                SettingValue v = settings.get(RlSettings.DEFAULT_EXPLORATION_RATE, SettingContext.tenant());
                if ((v.source() == SettingSource.TENANT || v.source() == SettingSource.AGENT) && v.value() instanceof Number n) {
                    // The column is numeric(4,3): 0.000 to 1.000.
                    return BigDecimal.valueOf(n.doubleValue()).setScale(3, RoundingMode.HALF_UP)
                        .max(BigDecimal.ZERO).min(BigDecimal.ONE);
                }
            } catch (RuntimeException e) {
                log.warn("Could not read the RL starting exploration rate setting, using the configured rate: {}", e.toString());
            }
        }
        return properties.getDefaultExplorationRate();
    }

    @Transactional(readOnly = true)
    public List<AgentPolicy> listPolicies(UUID agentId) {
        return policyRepository.findByAgentId(agentId);
    }

    @Transactional(readOnly = true)
    public Optional<AgentPolicy> findPolicy(UUID agentId, PolicyDimension dimension) {
        return policyRepository.findByAgentIdAndDimension(agentId, dimension);
    }

    @Transactional(readOnly = true)
    public List<PolicyArm> listArms(UUID policyId) {
        return armRepository.findByPolicyId(policyId);
    }

    /**
     * Ensures a policy row exists for every dimension of this agent (seeding
     * default arms on first creation), then returns all of them - so the admin
     * UI always has the full set to toggle even for a brand-new agent.
     */
    @Transactional
    public List<AgentPolicy> ensureAllPolicies(UUID agentId) {
        for (PolicyDimension dimension : PolicyDimension.values()) {
            ensurePolicy(agentId, dimension);
        }
        return policyRepository.findByAgentId(agentId);
    }

    /** Creates the policy + its default arms for one dimension if absent; returns the (existing or new) policy. */
    @Transactional
    public AgentPolicy ensurePolicy(UUID agentId, PolicyDimension dimension) {
        Optional<AgentPolicy> existing = policyRepository.findByAgentIdAndDimension(agentId, dimension);
        if (existing.isPresent()) {
            return existing.get();
        }
        AgentPolicy policy = policyRepository.save(new AgentPolicy(agentId, dimension, false, startingExplorationRate()));
        ensureDefaultArms(policy);
        return policy;
    }

    /** Seeds the code-defined default arms for a policy's dimension (no-op for any that already exist). */
    @Transactional
    public void ensureDefaultArms(AgentPolicy policy) {
        switch (policy.getDimension()) {
            case REASONING_DEPTH -> {
                for (String depth : properties.getReasoningDepthArms()) {
                    if (depth != null && !depth.isBlank()) {
                        seedArm(policy.getId(), depth.trim(), "{\"reasoningDepth\":\"" + depth.trim() + "\"}");
                    }
                }
            }
            case GEN_PARAMS -> {
                properties.genParamPresets().forEach((name, preset) -> seedArm(
                    policy.getId(), name, "{\"temperature\":" + preset.getTemperature() + ",\"topP\":" + preset.getTopP() + "}"));
            }
            case MODEL_ROUTE -> {
                for (String model : properties.getModelRouteArms()) {
                    if (model != null && !model.isBlank()) {
                        seedArm(policy.getId(), model.trim(), "{\"model\":\"" + model.trim() + "\"}");
                    }
                }
            }
            case TOOL_SUBSET -> seedArm(policy.getId(), "ALL", "{\"toolSubset\":\"ALL\"}");
            case PROMPT_VARIANT -> seedArm(policy.getId(), "DEFAULT", "{\"promptVersionId\":null}");
        }
    }

    /** Adds an arm to a policy (used by the orchestrator to register PROMPT_VARIANT arms referencing real prompt-version ids). */
    @Transactional
    public PolicyArm addArm(UUID policyId, String armKey, String armConfigJson, String contextBucket) {
        return armRepository.findFirstByPolicyIdAndArmKey(policyId, armKey)
            .orElseGet(() -> armRepository.save(new PolicyArm(policyId, armKey, armConfigJson, contextBucket)));
    }

    /**
     * Unconditionally creates an arm - the admin API's counterpart to {@link
     * #addArm}, which is deliberately idempotent for the orchestrator's
     * register-if-absent use. An operator POSTing a key that already exists
     * wants to be TOLD (409), not silently handed the pre-existing arm, so
     * {@link PolicyArmAdminService} checks {@link #findArmByKey} first and
     * calls this.
     */
    @Transactional
    public PolicyArm createArm(UUID policyId, String armKey, String armConfigJson, String contextBucket) {
        return armRepository.save(new PolicyArm(policyId, armKey, armConfigJson, contextBucket));
    }

    /** One arm of a policy by its operator-facing key, if present. */
    @Transactional(readOnly = true)
    public Optional<PolicyArm> findArmByKey(UUID policyId, String armKey) {
        return armRepository.findFirstByPolicyIdAndArmKey(policyId, armKey);
    }

    /**
     * One arm by id. Schema-per-tenant means this can only ever resolve an arm
     * inside the CALLER's own schema - see {@code PolicyArm}'s javadoc. Callers
     * must still confirm the arm belongs to the policy they addressed, so an
     * arm id from a different policy of the same tenant isn't deletable through
     * another dimension's URL.
     */
    @Transactional(readOnly = true)
    public Optional<PolicyArm> findArmById(UUID armId) {
        return armRepository.findById(armId);
    }

    /**
     * Removes an arm. The arm's Beta posterior goes with it - deleting is how
     * an operator retires a route that turned out to be bad, and there is
     * nothing worth keeping in a posterior nobody will ever sample again.
     * Historical {@code rl_episode} rows keep their {@code policy_arm_id}
     * reference (the column is not a hard FK), so past attribution is not
     * rewritten by a delete.
     */
    @Transactional
    public void deleteArm(PolicyArm arm) {
        armRepository.delete(arm);
    }

    private void seedArm(UUID policyId, String armKey, String armConfigJson) {
        if (armRepository.findFirstByPolicyIdAndArmKey(policyId, armKey).isEmpty()) {
            armRepository.save(new PolicyArm(policyId, armKey, armConfigJson, null));
        }
    }

    @Transactional
    public AgentPolicy updatePolicy(UUID agentId, PolicyDimension dimension, boolean enabled, BigDecimal explorationRate) {
        return updatePolicy(agentId, dimension, enabled, explorationRate, null);
    }

    @Transactional
    public AgentPolicy updatePolicy(
        UUID agentId, PolicyDimension dimension, boolean enabled, BigDecimal explorationRate, BanditAlgorithm algorithm
    ) {
        AgentPolicy policy = ensurePolicy(agentId, dimension);
        policy.setEnabled(enabled);
        if (explorationRate != null) {
            // Clamp to [0,1] - it's a probability floor.
            BigDecimal clamped = explorationRate.max(BigDecimal.ZERO).min(BigDecimal.ONE);
            policy.setExplorationRate(clamped);
        }
        if (algorithm != null) {
            policy.setAlgorithm(algorithm);
        }
        return policyRepository.save(policy);
    }

    /** Resolves a specific arm by (agent, dimension, armKey) - used to apply reward from the integration port. */
    @Transactional(readOnly = true)
    public Optional<PolicyArm> findArm(UUID agentId, PolicyDimension dimension, String armKey) {
        return policyRepository.findByAgentIdAndDimension(agentId, dimension)
            .flatMap(p -> armRepository.findFirstByPolicyIdAndArmKey(p.getId(), armKey));
    }
}
