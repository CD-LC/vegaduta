package com.agenticai.core.rl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One dimension's arm selection for an {@link Episode} (migration
 * V201__rl_episode_policy_arm.sql). {@link Episode#getPolicyArmId()} can only
 * remember a single arm - fine while {@link PolicyDimension#MODEL_ROUTE} was
 * the only dimension the orchestrator ever consulted, but a turn can now
 * select up to one arm per {@link PolicyDimension}. This is the full record:
 * one row per (episode, dimension) actually selected that turn, so the later
 * human-reward path ({@code FeedbackService#applyHumanReward}) can fold the
 * turn's one observed reward back into every arm that contributed to it, not
 * just whichever one happened to occupy {@code Episode.policyArmId}.
 *
 * <p>{@code Episode.policyArmId} is still populated too, from the
 * {@link PolicyDimension#MODEL_ROUTE} selection specifically - additive, not
 * a replacement, so existing readers of that column are unaffected.
 */
@Entity
@Table(name = "rl_episode_policy_arm")
public class EpisodePolicyArm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "episode_id", nullable = false)
    private UUID episodeId;

    @Column(name = "policy_arm_id", nullable = false)
    private UUID policyArmId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension", nullable = false, length = 32)
    private PolicyDimension dimension;

    protected EpisodePolicyArm() {
        // JPA
    }

    public EpisodePolicyArm(UUID episodeId, UUID policyArmId, PolicyDimension dimension) {
        this.episodeId = episodeId;
        this.policyArmId = policyArmId;
        this.dimension = dimension;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEpisodeId() {
        return episodeId;
    }

    public UUID getPolicyArmId() {
        return policyArmId;
    }

    public PolicyDimension getDimension() {
        return dimension;
    }
}
