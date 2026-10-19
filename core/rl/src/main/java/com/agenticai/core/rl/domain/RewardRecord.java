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
import java.time.Instant;
import java.util.UUID;

/**
 * One component of an {@link Episode}'s composite reward, from a single
 * {@link RewardSource}. Kept as its own row (not just folded into the blended
 * scalar) so the composite reward is always reconstructable and auditable from
 * its parts - the verifiable-reward / anti-reward-hacking posture.
 *
 * <p>Schema-per-tenant: no tenant_id column (see {@code V111__rl_reward_record.sql}).
 */
@Entity
@Table(name = "rl_reward_record")
public class RewardRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "episode_id", nullable = false)
    private UUID episodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 24)
    private RewardSource source;

    @Column(name = "value", nullable = false, precision = 8, scale = 4)
    private BigDecimal value;

    @Column(name = "weight", nullable = false, precision = 6, scale = 4)
    private BigDecimal weight;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RewardRecord() {
        // JPA
    }

    public RewardRecord(UUID episodeId, RewardSource source, BigDecimal value, BigDecimal weight, Instant createdAt) {
        this.episodeId = episodeId;
        this.source = source;
        this.value = value;
        this.weight = weight;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEpisodeId() {
        return episodeId;
    }

    public RewardSource getSource() {
        return source;
    }

    public BigDecimal getValue() {
        return value;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
