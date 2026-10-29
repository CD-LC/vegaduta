package com.agenticai.core.rl.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The {@code core/rl} schema-per-tenant entities. Mostly carriers, with two pieces of real logic
 * worth pinning: {@link PolicyArm#applyReward}'s clamped Beta posterior update (the numbers every
 * Thompson draw depends on) and {@link AgentPolicy}'s null-to-THOMPSON defaulting.
 */
class RlDomainEntitiesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    // ---- PolicyArm: the Beta posterior update ----

    @Test
    void aNewArmStartsOnTheUniformPrior() {
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "BALANCED", "{\"temperature\":0.7}", null);

        assertEquals(1.0, arm.getAlpha());
        assertEquals(1.0, arm.getBeta());
        assertEquals(0L, arm.getPulls());
        assertEquals(0.0, arm.getRewardSum());
        assertTrue(arm.isEnabled());
        assertNull(arm.getContextBucket());
        assertEquals(0.5, arm.avgReward(), "never pulled: the prior mean, not 0");
        assertNull(arm.getId());
    }

    @Test
    void applyRewardUpdatesThePosteriorLikeTheContractSays() {
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "k", "{}", "bucket-a");

        arm.applyReward(0.8);
        arm.applyReward(0.2);

        // alpha += r, beta += (1 - r), pulls +1, rewardSum += r per observation.
        assertEquals(1.0 + 0.8 + 0.2, arm.getAlpha(), 1e-9);
        assertEquals(1.0 + 0.2 + 0.8, arm.getBeta(), 1e-9);
        assertEquals(2L, arm.getPulls());
        assertEquals(1.0, arm.getRewardSum(), 1e-9);
        assertEquals(0.5, arm.avgReward(), 1e-9);
        assertEquals("bucket-a", arm.getContextBucket());
    }

    @Test
    void rewardsOutsideTheUnitIntervalAreClampedBeforeUpdating() {
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "k", "{}", null);

        arm.applyReward(1.5);   // clamps to 1.0
        arm.applyReward(-0.5);  // clamps to 0.0

        assertEquals(1.0 + 1.0 + 0.0, arm.getAlpha(), 1e-9);
        assertEquals(1.0 + 0.0 + 1.0, arm.getBeta(), 1e-9);
        assertEquals(2L, arm.getPulls());
        assertEquals(1.0, arm.getRewardSum(), 1e-9);
    }

    @Test
    void armConfigAndEnabledAreTheOnlyMutableFields() {
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "k", "{}", null);
        arm.setArmConfigJson("{\"model\":\"m2\"}");
        arm.setEnabled(false);

        assertEquals("{\"model\":\"m2\"}", arm.getArmConfigJson());
        assertFalse(arm.isEnabled());
        assertEquals("k", arm.getArmKey());
    }

    // ---- AgentPolicy ----

    @Test
    void aPolicyDefaultsToThompsonWhenNoAlgorithmIsGiven() {
        UUID agentId = UUID.randomUUID();

        AgentPolicy four = new AgentPolicy(agentId, PolicyDimension.MODEL_ROUTE, true,
            new BigDecimal("0.10"));
        AgentPolicy five = new AgentPolicy(agentId, PolicyDimension.GEN_PARAMS, false,
            new BigDecimal("0.25"), null);

        assertEquals(BanditAlgorithm.THOMPSON, four.getAlgorithm());
        assertEquals(BanditAlgorithm.THOMPSON, five.getAlgorithm());
        assertEquals(agentId, five.getAgentId());
        assertFalse(five.isEnabled());
        assertEquals(new BigDecimal("0.25"), five.getExplorationRate());

        five.setAlgorithm(BanditAlgorithm.UCB1);
        five.setAlgorithm(null);
        assertEquals(BanditAlgorithm.THOMPSON, five.getAlgorithm(), "null reverts to THOMPSON");
        five.setEnabled(true);
        five.setExplorationRate(new BigDecimal("0.5"));
        assertTrue(five.isEnabled());
        assertEquals(new BigDecimal("0.5"), five.getExplorationRate());
    }

    // ---- Episode ----

    @Test
    void anEpisodeStartsNonTerminalWithMutableRewardAndOutcome() {
        UUID agentId = UUID.randomUUID();
        UUID armId = UUID.randomUUID();
        Episode ep = new Episode(agentId, "sess-1", "turn-7", NOW, "{\"k\":1}", armId);

        assertEquals(agentId, ep.getAgentId());
        assertEquals("sess-1", ep.getSessionId());
        assertEquals("turn-7", ep.getTurnRef());
        assertEquals(NOW, ep.getStartedAt());
        assertEquals("{\"k\":1}", ep.getContextJson());
        assertEquals(armId, ep.getPolicyArmId());
        assertFalse(ep.isTerminal());
        assertNull(ep.getEndedAt());
        assertNull(ep.getReward());
        assertNull(ep.getSubject());

        ep.setSubject("alice@example.com");
        ep.setEndedAt(NOW.plusSeconds(5));
        ep.setReward(new BigDecimal("0.75"));
        ep.setRewardBreakdownJson("{\"HUMAN\":1.0}");
        ep.setTerminal(true);
        ep.setPolicyArmId(null);

        assertEquals("alice@example.com", ep.getSubject());
        assertEquals(NOW.plusSeconds(5), ep.getEndedAt());
        assertEquals(new BigDecimal("0.75"), ep.getReward());
        assertEquals("{\"HUMAN\":1.0}", ep.getRewardBreakdownJson());
        assertTrue(ep.isTerminal());
        assertNull(ep.getPolicyArmId());
    }

    // ---- TurnFeedback ----

    @Test
    void turnFeedbackCarriesTheSignalExactlyAsStored() {
        UUID agentId = UUID.randomUUID();
        TurnFeedback f = new TurnFeedback(agentId, "s1", "t1", FeedbackSignal.RATING,
            (short) 4, "decent", null, "alice@example.com", NOW);

        assertEquals(agentId, f.getAgentId());
        assertEquals("s1", f.getSessionId());
        assertEquals("t1", f.getTurnRef());
        assertEquals(FeedbackSignal.RATING, f.getSignal());
        assertEquals(Short.valueOf((short) 4), f.getRating());
        assertEquals("decent", f.getComment());
        assertNull(f.getEditedAnswer());
        assertEquals("alice@example.com", f.getCreatedBy());
        assertEquals(NOW, f.getCreatedAt());
        assertNull(f.getId());
    }

    // ---- TrajectoryStep ----

    @Test
    void aTrajectoryStepCarriesItsOrderingAndTelemetry() {
        UUID episodeId = UUID.randomUUID();
        TrajectoryStep step = new TrajectoryStep(episodeId, 3, "tool", "tool_result",
            "{\"out\":1}", "search", 412L, 87L, NOW);

        assertEquals(episodeId, step.getEpisodeId());
        assertEquals(3, step.getStepIndex());
        assertEquals("tool", step.getRole());
        assertEquals("tool_result", step.getActionType());
        assertEquals("{\"out\":1}", step.getContent());
        assertEquals("search", step.getToolName());
        assertEquals(412L, step.getTokens());
        assertEquals(87L, step.getLatencyMs());
        assertEquals(NOW, step.getCreatedAt());
        assertNull(step.getId());
    }

    // ---- RewardRecord ----

    @Test
    void aRewardRecordIsAnImmutableComponentOfTheComposite() {
        UUID episodeId = UUID.randomUUID();
        RewardRecord r = new RewardRecord(episodeId, RewardSource.JUDGE,
            new BigDecimal("0.9"), new BigDecimal("0.6"), NOW);

        assertEquals(episodeId, r.getEpisodeId());
        assertEquals(RewardSource.JUDGE, r.getSource());
        assertEquals(new BigDecimal("0.9"), r.getValue());
        assertEquals(new BigDecimal("0.6"), r.getWeight());
        assertEquals(NOW, r.getCreatedAt());
    }

    // ---- EpisodePolicyArm ----

    @Test
    void anEpisodePolicyArmRecordsOneDimensionsSelection() {
        UUID episodeId = UUID.randomUUID();
        UUID armId = UUID.randomUUID();
        EpisodePolicyArm link = new EpisodePolicyArm(episodeId, armId, PolicyDimension.REASONING_DEPTH);

        assertEquals(episodeId, link.getEpisodeId());
        assertEquals(armId, link.getPolicyArmId());
        assertEquals(PolicyDimension.REASONING_DEPTH, link.getDimension());
        assertNull(link.getId());
    }

    // ---- enums: the persisted/wire values ----

    @Test
    void theEnumsCarryExactlyTheValuesTheSchemaAndFrontendExpect() {
        // FeedbackSignal names ARE the wire values (TurnFeedback.tsx posts them verbatim and the
        // column is VARCHAR(16) - the longest name is the constraint).
        assertEquals(6, FeedbackSignal.values().length);
        for (FeedbackSignal s : FeedbackSignal.values()) {
            assertTrue(s.name().length() <= 16, s + " must fit turn_feedback.signal");
            assertEquals(s, FeedbackSignal.valueOf(s.name()));
        }
        assertEquals(FeedbackSignal.THUMBS_UP, FeedbackSignal.valueOf("THUMBS_UP"));

        assertEquals(5, PolicyDimension.values().length);
        assertEquals(PolicyDimension.MODEL_ROUTE, PolicyDimension.valueOf("MODEL_ROUTE"));

        assertEquals(2, BanditAlgorithm.values().length);
        assertEquals(BanditAlgorithm.UCB1, BanditAlgorithm.valueOf("UCB1"));

        // SAFETY is the veto component - it must stay a distinct source, never blended.
        assertEquals(6, RewardSource.values().length);
        assertEquals(RewardSource.SAFETY, RewardSource.valueOf("SAFETY"));
    }
}
