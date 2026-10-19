package com.agenticai.core.rl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One arm of an {@link AgentPolicy}'s bandit, carrying its Thompson-sampling
 * Beta(alpha, beta) posterior. {@code alpha}/{@code beta} start at 1.0 (uniform
 * prior); on each observed reward r in [0,1]: {@code alpha += r},
 * {@code beta += (1 - r)}, {@code pulls += 1}, {@code rewardSum += r}.
 * {@code armConfigJson} is the opaque config this arm selects;
 * {@code contextBucket} scopes the posterior to a coarse context slice
 * (null/empty = the global bucket).
 *
 * <p>Schema-per-tenant: no tenant_id column (see {@code V113__policy_arm.sql}).
 */
@Entity
@Table(name = "policy_arm")
public class PolicyArm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "arm_key", nullable = false, length = 128)
    private String armKey;

    @Column(name = "arm_config_json", columnDefinition = "TEXT")
    private String armConfigJson;

    @Column(name = "alpha", nullable = false)
    private double alpha;

    @Column(name = "beta", nullable = false)
    private double beta;

    @Column(name = "pulls", nullable = false)
    private long pulls;

    @Column(name = "reward_sum", nullable = false)
    private double rewardSum;

    @Column(name = "context_bucket", length = 64)
    private String contextBucket;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected PolicyArm() {
        // JPA
    }

    public PolicyArm(UUID policyId, String armKey, String armConfigJson, String contextBucket) {
        this.policyId = policyId;
        this.armKey = armKey;
        this.armConfigJson = armConfigJson;
        this.contextBucket = contextBucket;
        this.alpha = 1.0;
        this.beta = 1.0;
        this.pulls = 0L;
        this.rewardSum = 0.0;
        this.enabled = true;
    }

    /** Applies one observed reward in [0,1] to the Beta posterior. */
    public void applyReward(double reward) {
        double r = reward < 0.0 ? 0.0 : (reward > 1.0 ? 1.0 : reward);
        this.alpha += r;
        this.beta += (1.0 - r);
        this.pulls += 1;
        this.rewardSum += r;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public String getArmKey() {
        return armKey;
    }

    public String getArmConfigJson() {
        return armConfigJson;
    }

    public void setArmConfigJson(String armConfigJson) {
        this.armConfigJson = armConfigJson;
    }

    public double getAlpha() {
        return alpha;
    }

    public double getBeta() {
        return beta;
    }

    public long getPulls() {
        return pulls;
    }

    public double getRewardSum() {
        return rewardSum;
    }

    public String getContextBucket() {
        return contextBucket;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Mean observed reward so far, or the prior mean (0.5) when never pulled. */
    public double avgReward() {
        return pulls == 0 ? 0.5 : rewardSum / pulls;
    }
}
