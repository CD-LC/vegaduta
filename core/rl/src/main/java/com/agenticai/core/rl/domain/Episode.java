package com.agenticai.core.rl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One bandit episode: a single agent turn whose outcome is learned from. {@code contextJson}
 * captures the context/features at decision time, {@code policyArmId} is whichever {@link
 * PolicyArm} was selected for the turn (null when no policy was active and the caller used its
 * default), {@code reward} is the verifiable composite reward CLIPPED to [0,1], and {@code
 * rewardBreakdownJson} keeps the raw per-source breakdown that produced it (audit + reward-hacking
 * forensics).
 *
 * <p>Schema-per-tenant: no tenant_id column (see {@code V109__rl_episode.sql}).
 */
@Entity
@Table(name = "rl_episode")
public class Episode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "turn_ref", nullable = false, length = 128)
    private String turnRef;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "policy_arm_id")
    private UUID policyArmId;

    @Column(name = "reward", precision = 6, scale = 4)
    private BigDecimal reward;

    @Column(name = "reward_breakdown_json", columnDefinition = "TEXT")
    private String rewardBreakdownJson;

    @Column(name = "terminal", nullable = false)
    private boolean terminal;

    /** Who had this turn; null for episodes recorded before the column existed (V474). */
    @Column(name = "subject")
    private String subject;

    protected Episode() {
        // JPA
    }

    public Episode(
            UUID agentId,
            String sessionId,
            String turnRef,
            Instant startedAt,
            String contextJson,
            UUID policyArmId) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.turnRef = turnRef;
        this.startedAt = startedAt;
        this.contextJson = contextJson;
        this.policyArmId = policyArmId;
        this.terminal = false;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTurnRef() {
        return turnRef;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public String getContextJson() {
        return contextJson;
    }

    public UUID getPolicyArmId() {
        return policyArmId;
    }

    public void setPolicyArmId(UUID policyArmId) {
        this.policyArmId = policyArmId;
    }

    public BigDecimal getReward() {
        return reward;
    }

    public void setReward(BigDecimal reward) {
        this.reward = reward;
    }

    public String getRewardBreakdownJson() {
        return rewardBreakdownJson;
    }

    public void setRewardBreakdownJson(String rewardBreakdownJson) {
        this.rewardBreakdownJson = rewardBreakdownJson;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public void setTerminal(boolean terminal) {
        this.terminal = terminal;
    }
}
