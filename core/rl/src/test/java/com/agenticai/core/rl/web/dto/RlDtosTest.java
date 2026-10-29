package com.agenticai.core.rl.web.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.rl.domain.BanditAlgorithm;
import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.web.dto.RlPreferenceExport.DpoPair;
import com.agenticai.core.rl.web.dto.RlPreferenceExport.KtoSample;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.ArmStat;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.FeedbackCounts;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.PolicyState;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.RewardPoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The {@code web/dto} records are the API's fixed shape: {@code PolicyArmResponse.from} and {@code
 * TurnFeedbackResponse.from} are the entity-to-wire mapping, and the rest pin the component names
 * the web dashboard and the trainer export read.
 */
class RlDtosTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void policyArmResponseMapsTheEntityOneToOne() {
        UUID armId = UUID.randomUUID();
        PolicyArm arm = new PolicyArm(UUID.randomUUID(), "PRECISE", "{\"temperature\":0.2}", "bkt");
        ReflectionTestUtils.setField(arm, "id", armId);
        arm.applyReward(0.6);

        PolicyArmResponse r = PolicyArmResponse.from(arm);

        assertEquals(armId, r.id());
        assertEquals("PRECISE", r.armKey());
        assertEquals("{\"temperature\":0.2}", r.armConfigJson());
        assertEquals("bkt", r.contextBucket());
        assertEquals(1L, r.pulls());
        assertEquals(1.6, r.alpha(), 1e-9);
        assertEquals(1.4, r.beta(), 1e-9);
    }

    @Test
    void turnFeedbackResponseMapsEveryColumn() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        TurnFeedback f =
                new TurnFeedback(
                        agentId,
                        "s1",
                        "t1",
                        FeedbackSignal.EDIT,
                        null,
                        "fix this",
                        "corrected answer",
                        "alice",
                        NOW);
        ReflectionTestUtils.setField(f, "id", id);

        TurnFeedbackResponse r = TurnFeedbackResponse.from(f);

        assertEquals(id, r.id());
        assertEquals(agentId, r.agentId());
        assertEquals("s1", r.sessionId());
        assertEquals("t1", r.turnRef());
        assertEquals(FeedbackSignal.EDIT, r.signal());
        assertNull(r.rating());
        assertEquals("fix this", r.comment());
        assertEquals("corrected answer", r.editedAnswer());
        assertEquals(NOW, r.createdAt());
    }

    @Test
    void requestRecordsCarryTheValidatedFields() {
        PolicyArmRequest armReq =
                new PolicyArmRequest("FAST", "{\"reasoningDepth\":\"FAST\"}", null);
        assertEquals("FAST", armReq.armKey());
        assertEquals("{\"reasoningDepth\":\"FAST\"}", armReq.armConfigJson());
        assertNull(armReq.contextBucket());

        TurnFeedbackRequest fbReq =
                new TurnFeedbackRequest("s1", "t1", FeedbackSignal.THUMBS_UP, null, "nice", null);
        assertEquals("s1", fbReq.sessionId());
        assertEquals(FeedbackSignal.THUMBS_UP, fbReq.signal());
        assertNull(fbReq.rating());

        PolicyUpdateRequest upd =
                new PolicyUpdateRequest(true, new BigDecimal("0.2"), BanditAlgorithm.UCB1);
        assertTrue(upd.enabled());
        assertEquals(new BigDecimal("0.2"), upd.explorationRate());
        assertEquals(BanditAlgorithm.UCB1, upd.algorithm());
    }

    @Test
    void theSummaryResponseNestsItsDashboardSections() {
        UUID policyId = UUID.randomUUID();
        UUID armId = UUID.randomUUID();
        RlSummaryResponse r =
                new RlSummaryResponse(
                        new FeedbackCounts(3, 1, 2, 1, 4, 0, 4.5, 11),
                        List.of(new RewardPoint("2026-01-01", 0.8, 5)),
                        List.of(
                                new ArmStat(
                                        "GEN_PARAMS",
                                        policyId,
                                        armId,
                                        "BALANCED",
                                        "{}",
                                        null,
                                        12L,
                                        0.6,
                                        2.0,
                                        1.0,
                                        true)),
                        List.of(
                                new PolicyState(
                                        "GEN_PARAMS",
                                        policyId,
                                        true,
                                        new BigDecimal("0.1"),
                                        "THOMPSON")));

        assertEquals(3, r.feedbackCounts().up());
        assertEquals(4.5, r.feedbackCounts().avgRating());
        assertEquals("2026-01-01", r.rewardTrend().get(0).date());
        assertEquals("BALANCED", r.arms().get(0).armKey());
        assertTrue(r.arms().get(0).enabled());
        assertEquals("THOMPSON", r.policies().get(0).algorithm());
    }

    @Test
    void thePreferenceExportCarriesBothTrainingFormats() {
        UUID agentId = UUID.randomUUID();
        RlPreferenceExport r =
                new RlPreferenceExport(
                        agentId,
                        1,
                        2,
                        List.of(new DpoPair("prompt", "chosen-text", "rejected-text")),
                        List.of(
                                new KtoSample("p", "c", true, "THUMBS_UP"),
                                new KtoSample("p2", "c2", false, "RATING")));

        assertEquals(agentId, r.agentId());
        assertEquals(1, r.dpoCount());
        assertEquals(2, r.ktoCount());
        assertEquals("chosen-text", r.dpo().get(0).chosen());
        assertEquals("rejected-text", r.dpo().get(0).rejected());
        assertTrue(r.kto().get(0).desirable());
        assertEquals("RATING", r.kto().get(1).source());
    }
}
