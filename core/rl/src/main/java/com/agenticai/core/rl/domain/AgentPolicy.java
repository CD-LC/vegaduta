package com.agenticai.core.rl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A per-agent, per-{@link PolicyDimension} bandit policy. {@code enabled}
 * defaults false: nothing explores on this dimension until an admin turns it
 * on (a disabled policy makes {@code ContextualBanditService.selectArm} return
 * empty, so the caller keeps using its own default). {@code explorationRate}
 * is the epsilon-floor for forced uniform exploration layered on top of
 * Thompson sampling.
 *
 * <p>Schema-per-tenant: no tenant_id column (see {@code V112__agent_policy.sql}).
 */
@Entity
@Table(name = "agent_policy")
public class AgentPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension", nullable = false, length = 32)
    private PolicyDimension dimension;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "exploration_rate", nullable = false, precision = 4, scale = 3)
    private BigDecimal explorationRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm", nullable = false, length = 16)
    private BanditAlgorithm algorithm = BanditAlgorithm.THOMPSON;

    protected AgentPolicy() {
        // JPA
    }

    public AgentPolicy(UUID agentId, PolicyDimension dimension, boolean enabled, BigDecimal explorationRate) {
        this(agentId, dimension, enabled, explorationRate, BanditAlgorithm.THOMPSON);
    }

    public AgentPolicy(UUID agentId, PolicyDimension dimension, boolean enabled, BigDecimal explorationRate, BanditAlgorithm algorithm) {
        this.agentId = agentId;
        this.dimension = dimension;
        this.enabled = enabled;
        this.explorationRate = explorationRate;
        this.algorithm = algorithm == null ? BanditAlgorithm.THOMPSON : algorithm;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public PolicyDimension getDimension() {
        return dimension;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public BigDecimal getExplorationRate() {
        return explorationRate;
    }

    public void setExplorationRate(BigDecimal explorationRate) {
        this.explorationRate = explorationRate;
    }

    public BanditAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(BanditAlgorithm algorithm) {
        this.algorithm = algorithm == null ? BanditAlgorithm.THOMPSON : algorithm;
    }
}
