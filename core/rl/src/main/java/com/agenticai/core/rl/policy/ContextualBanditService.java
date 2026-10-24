package com.agenticai.core.rl.policy;

import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.BanditAlgorithm;
import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.repo.AgentPolicyRepository;
import com.agenticai.core.rl.repo.PolicyArmRepository;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-agent contextual bandit. {@link #selectArm} picks an enabled arm for
 * a (policy, context bucket) by THOMPSON SAMPLING from each arm's Beta(alpha,
 * beta) posterior, with an epsilon-floor of forced uniform exploration on top;
 * {@link #update} folds one observed reward back into the chosen arm's
 * posterior.
 *
 * <p>Safety: only the SAFE, non-side-effecting {@link PolicyDimension}s are
 * explorable (see {@link #EXPLORABLE}). A disabled {@link AgentPolicy} makes
 * {@link #selectArm} return empty, meaning "no decision" - the caller keeps
 * using its own default. This service never itself takes a side-effecting
 * action; it only returns which config arm to use.
 */
@Service
public class ContextualBanditService {

    /**
     * The dimensions this bandit is allowed to explore. Every current
     * {@link PolicyDimension} is safe by construction; this guard exists so a
     * future, riskier dimension can be added to the enum without automatically
     * becoming explorable - it must be added here deliberately.
     */
    private static final EnumSet<PolicyDimension> EXPLORABLE = EnumSet.of(
        PolicyDimension.MODEL_ROUTE,
        PolicyDimension.REASONING_DEPTH,
        PolicyDimension.PROMPT_VARIANT,
        PolicyDimension.TOOL_SUBSET,
        PolicyDimension.GEN_PARAMS
    );

    /** Hard cap on the de-dup set below - a runaway agent-id space can never grow this unboundedly. */
    private static final int MAX_WARNED_POLICIES = 512;

    private static final Logger log = LoggerFactory.getLogger(ContextualBanditService.class);

    /** (tenant|agent|dimension) keys already warned about; see warnOnceAboutEmptyEnabledPolicy. */
    private final ConcurrentHashMap<String, Long> warnedEmptyPolicies = new ConcurrentHashMap<>();

    /** Minimum gap between two warnings for the same (tenant, agent, dimension). */
    static final long WARN_INTERVAL_NANOS = java.util.concurrent.TimeUnit.HOURS.toNanos(1);

    private java.util.function.LongSupplier nanoClock = System::nanoTime;

    /** Test seam for the warning interval. */
    void setNanoClockForTest(java.util.function.LongSupplier clock) {
        this.nanoClock = clock;
    }

    private final AgentPolicyRepository policyRepository;
    private final PolicyArmRepository armRepository;

    private RlPolicyProperties properties = new RlPolicyProperties();

    public ContextualBanditService(AgentPolicyRepository policyRepository, PolicyArmRepository armRepository) {
        this.policyRepository = policyRepository;
        this.armRepository = armRepository;
    }

    /** {@code rl.policy.*}: UCB1's exploration constant. Without it the textbook 2.0 applies. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProperties(RlPolicyProperties properties) {
        this.properties = properties == null ? new RlPolicyProperties() : properties;
    }

    private SettingsResolver settings;

    /**
     * The settings catalog ({@code rl.policy.*}): a workspace's or an agent's UCB1 exploration constant replaces the
     * property value. Without it the property applies.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettings(SettingsResolver settings) {
        this.settings = settings;
    }

    /** UCB1's exploration constant for {@code agentId} under the calling thread's workspace settings. */
    private double ucb1ExplorationConstant(UUID agentId) {
        if (settings != null) {
            try {
                return settings.getDecimal(RlSettings.UCB1_EXPLORATION_CONSTANT, SettingContext.forAgent(agentId));
            } catch (RuntimeException e) {
                // A selection never fails because a setting cannot be read: the configured constant applies.
                log.warn("Could not read the RL exploration setting, using the configured constant: {}", e.toString());
            }
        }
        return properties.getUcb1ExplorationConstant();
    }

    /**
     * Selects an arm for the given agent/dimension/context bucket, or empty
     * when there is no enabled policy, the dimension is not explorable, or no
     * enabled arms exist (in every "empty" case the caller uses its default).
     */
    @Transactional(readOnly = true)
    public Optional<PolicyArm> selectArm(UUID agentId, PolicyDimension dimension, String contextBucket) {
        if (!EXPLORABLE.contains(dimension)) {
            return Optional.empty();
        }
        AgentPolicy policy = policyRepository.findByAgentIdAndDimension(agentId, dimension).orElse(null);
        return chooseArm(policy, agentId, dimension, contextBucket);
    }

    /**
     * Batched {@link #selectArm}: ONE policy query for the agent (instead of one per dimension) and, per
     * dimension, exactly the decision {@code selectArm} would have made (same guards, same arm query, same
     * random draw, arm statistics read fresh), so the result is distributed identically to N sequential
     * calls. Dimensions that are not explorable or have no policy row cost no further query.
     */
    @Transactional(readOnly = true)
    public java.util.Map<PolicyDimension, Optional<PolicyArm>> selectArms(
        UUID agentId, Set<PolicyDimension> dimensions, String contextBucket
    ) {
        java.util.Map<PolicyDimension, AgentPolicy> policies = new java.util.EnumMap<>(PolicyDimension.class);
        for (AgentPolicy p : policyRepository.findByAgentId(agentId)) {
            policies.putIfAbsent(p.getDimension(), p);
        }
        java.util.Map<PolicyDimension, Optional<PolicyArm>> out = new java.util.EnumMap<>(PolicyDimension.class);
        for (PolicyDimension dimension : dimensions) {
            out.put(dimension, EXPLORABLE.contains(dimension)
                ? chooseArm(policies.get(dimension), agentId, dimension, contextBucket)
                : Optional.empty());
        }
        return out;
    }

    private Optional<PolicyArm> chooseArm(
        AgentPolicy policy, UUID agentId, PolicyDimension dimension, String contextBucket
    ) {
        if (policy == null || !policy.isEnabled()) {
            return Optional.empty();
        }

        List<PolicyArm> candidates = armsForBucket(policy.getId(), contextBucket);
        if (candidates.isEmpty()) {
            warnOnceAboutEmptyEnabledPolicy(agentId, dimension);
            return Optional.empty();
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double explorationRate = policy.getExplorationRate() == null ? 0.0 : policy.getExplorationRate().doubleValue();
        if (rng.nextDouble() < explorationRate) {
            // Epsilon-floor: forced uniform exploration.
            return Optional.of(candidates.get(rng.nextInt(candidates.size())));
        }

        return policy.getAlgorithm() == BanditAlgorithm.UCB1
            ? selectByUcb1(candidates, ucb1ExplorationConstant(agentId))
            : selectByThompsonSampling(candidates, rng);
    }

    /** Thompson sampling: draw theta ~ Beta(alpha,beta) per arm, take the argmax sample. */
    private Optional<PolicyArm> selectByThompsonSampling(List<PolicyArm> candidates, ThreadLocalRandom rng) {
        PolicyArm best = null;
        double bestSample = Double.NEGATIVE_INFINITY;
        for (PolicyArm arm : candidates) {
            double sample = BetaSampler.sampleBeta(arm.getAlpha(), arm.getBeta(), rng);
            if (sample > bestSample) {
                bestSample = sample;
                best = arm;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * UCB1: score = avgReward + sqrt(c * ln(totalPulls) / armPulls) (c = rl.policy.ucb1-exploration-constant, 2.0, or the workspace's / agent's catalog override), argmax
     * wins. Any never-pulled arm gets an infinite score (visited first,
     * guaranteeing every arm is tried at least once before the confidence
     * term starts discriminating between them) - the standard UCB1
     * initialization, not a special case bolted on here.
     */
    private Optional<PolicyArm> selectByUcb1(List<PolicyArm> candidates, double explorationConstant) {
        long totalPulls = candidates.stream().mapToLong(PolicyArm::getPulls).sum();
        PolicyArm best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (PolicyArm arm : candidates) {
            double score;
            if (arm.getPulls() == 0) {
                score = Double.POSITIVE_INFINITY;
            } else {
                score = arm.avgReward() + Math.sqrt(explorationConstant * Math.log(Math.max(totalPulls, 1)) / arm.getPulls());
            }
            if (score > bestScore) {
                bestScore = score;
                best = arm;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Enabled arms whose context bucket matches the request, falling back to
     * the global bucket (null/blank context_bucket) when no bucket-specific arm
     * exists. When a bucket is requested, bucket-specific arms take precedence
     * over global ones.
     */
    private List<PolicyArm> armsForBucket(UUID policyId, String contextBucket) {
        List<PolicyArm> enabled = armRepository.findByPolicyIdAndEnabledTrue(policyId);
        if (contextBucket == null || contextBucket.isBlank()) {
            return globalArms(enabled);
        }
        List<PolicyArm> bucketArms = new ArrayList<>();
        for (PolicyArm arm : enabled) {
            if (contextBucket.equals(arm.getContextBucket())) {
                bucketArms.add(arm);
            }
        }
        return bucketArms.isEmpty() ? globalArms(enabled) : bucketArms;
    }

    private List<PolicyArm> globalArms(List<PolicyArm> enabled) {
        List<PolicyArm> global = new ArrayList<>();
        for (PolicyArm arm : enabled) {
            if (arm.getContextBucket() == null || arm.getContextBucket().isBlank()) {
                global.add(arm);
            }
        }
        return global;
    }

    /**
     * The signal that was missing when this shipped. An ENABLED policy with
     * zero selectable arms is a silent no-op - production had {@code
     * MODEL_ROUTE enabled=true, arms=0} for a real tenant, and nothing
     * anywhere said so; 97 episodes were recorded with zero arm attribution
     * and zero pulls before anyone noticed.
     *
     * <p>Logged at WARN at most once per hour per (tenant, agent, dimension) per process rather
     * than per turn - this runs on the hot chat path, and a per-turn warning
     * would be worse than no warning. The set is deliberately trivial: bounded
     * by a hard cap, never cleared, no eviction policy. It is a log de-duper,
     * not state anything reads (unlike the Redis-backed rate limiter/session
     * memory in this codebase, where per-instance state was a correctness bug -
     * here, each instance warning once is exactly the desired behavior).
     */
    private void warnOnceAboutEmptyEnabledPolicy(UUID agentId, PolicyDimension dimension) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("(no tenant in context)");
        String key = tenantId + "|" + agentId + "|" + dimension;
        long now = nanoClock.getAsLong();
        if (warnedEmptyPolicies.size() >= MAX_WARNED_POLICIES) {
            warnedEmptyPolicies.values().removeIf(last -> now - last >= WARN_INTERVAL_NANOS);
        }
        boolean[] due = {false};
        warnedEmptyPolicies.compute(key, (k, last) -> {
            if (last == null || now - last >= WARN_INTERVAL_NANOS) {
                due[0] = true;
                return now;
            }
            return last;
        });
        if (!due[0] || warnedEmptyPolicies.size() > MAX_WARNED_POLICIES) {
            return;
        }
        log.warn(
            "RL policy {} is ENABLED for agent {} (tenant {}) but has no selectable arms - every turn falls back to "
                + "the agent's default and no learning can happen. Add arms via "
                + "POST /api/rl/agents/{}/policies/{}/arms, or disable the policy.",
            dimension, agentId, tenantId, agentId, dimension
        );
    }

    /** Folds one observed reward in [0,1] into an arm's Beta posterior. */
    @Transactional
    public void update(UUID armId, double reward) {
        PolicyArm arm = armRepository.findById(armId).orElse(null);
        if (arm == null) {
            return;
        }
        arm.applyReward(reward);
        armRepository.save(arm);
    }
}
