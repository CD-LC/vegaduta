package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.tools.classification.TrifectaEnforcementPolicy.Action;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure rules behind trifecta enforcement: mode parsing, save-time action, exempt override, gated flag. */
class TrifectaEnforcementPolicyTest {

    private static LethalTrifectaDetector.Report report(List<String> priv, List<String> untrusted, List<String> egress) {
        return new LethalTrifectaDetector.Report(priv, untrusted, egress, List.of());
    }

    private static final LethalTrifectaDetector.Report LETHAL =
        report(List.of("read_crm"), List.of("fetch_web_page"), List.of("send_mail"));
    private static final LethalTrifectaDetector.Report TWO_LEGS =
        report(List.of("read_crm"), List.of(), List.of("send_mail"));
    private static final LethalTrifectaDetector.Report NONE = report(List.of(), List.of(), List.of());

    // ---- mode parsing -----------------------------------------------------

    @Test
    void parsesTheFourModesInAnySpelling() {
        assertEquals(TrifectaEnforcementMode.OFF, TrifectaEnforcementMode.parse("off"));
        assertEquals(TrifectaEnforcementMode.WARN, TrifectaEnforcementMode.parse("warn"));
        assertEquals(TrifectaEnforcementMode.REQUIRE_APPROVAL, TrifectaEnforcementMode.parse("require-approval"));
        assertEquals(TrifectaEnforcementMode.REQUIRE_APPROVAL, TrifectaEnforcementMode.parse(" REQUIRE_APPROVAL "));
        assertEquals(TrifectaEnforcementMode.BLOCK, TrifectaEnforcementMode.parse("Block"));
    }

    @Test
    void unknownBlankAndNullModesFallBackToWarn() {
        assertEquals(TrifectaEnforcementMode.WARN, TrifectaEnforcementMode.parse("strict"));
        assertEquals(TrifectaEnforcementMode.WARN, TrifectaEnforcementMode.parse(""));
        assertEquals(TrifectaEnforcementMode.WARN, TrifectaEnforcementMode.parse(null));
        assertFalse(TrifectaEnforcementMode.isRecognised("strict"));
        assertTrue(TrifectaEnforcementMode.isRecognised("block"));
        assertTrue(TrifectaEnforcementMode.isRecognised(""), "blank means 'use the default', not a typo");
    }

    @Test
    void wireNameIsTheHyphenatedConfigForm() {
        assertEquals("require-approval", TrifectaEnforcementMode.REQUIRE_APPROVAL.wireName());
        assertEquals("off", TrifectaEnforcementMode.OFF.wireName());
    }

    // ---- save-time action -------------------------------------------------

    @Test
    void offAndNonLethalSavesDoNothing() {
        assertEquals(Action.NONE, TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.OFF, NONE, LETHAL));
        for (TrifectaEnforcementMode mode : TrifectaEnforcementMode.values()) {
            assertEquals(Action.NONE, TrifectaEnforcementPolicy.decide(mode, LETHAL, TWO_LEGS), mode.name());
            assertEquals(Action.NONE, TrifectaEnforcementPolicy.decide(mode, NONE, null), mode.name());
        }
    }

    @Test
    void warnModeOnlyWarnsEvenWhenTheSaveMakesThingsWorse() {
        assertEquals(Action.WARN, TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.WARN, NONE, LETHAL));
    }

    @Test
    void requireApprovalForcesApprovalWheneverTheResultHoldsTheTrifecta() {
        assertEquals(Action.FORCE_APPROVAL,
            TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.REQUIRE_APPROVAL, TWO_LEGS, LETHAL));
        assertEquals(Action.FORCE_APPROVAL,
            TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.REQUIRE_APPROVAL, LETHAL, LETHAL));
    }

    @Test
    void blockRejectsASaveThatCompletesTheTrifecta() {
        assertEquals(Action.BLOCK, TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.BLOCK, TWO_LEGS, LETHAL));
        assertEquals(Action.BLOCK, TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.BLOCK, NONE, LETHAL));
    }

    @Test
    void blockNeverRejectsASaveThatRemovesALeg() {
        assertEquals(Action.NONE, TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.BLOCK, LETHAL, TWO_LEGS));
    }

    @Test
    void blockNeverRejectsASaveThatLeavesAnAlreadyLethalAgentNoWorse() {
        LethalTrifectaDetector.Report fewerTools = report(List.of("read_crm"), List.of("fetch_web_page"), List.of("send_mail"));
        LethalTrifectaDetector.Report before = report(
            List.of("read_crm", "read_kb"), List.of("fetch_web_page"), List.of("send_mail"));

        assertEquals(Action.WARN, TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.BLOCK, before, fewerTools));
        assertEquals(Action.WARN, TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.BLOCK, LETHAL, LETHAL));
    }

    @Test
    void blockRejectsAnAlreadyLethalAgentThatAddsAToolToALeg() {
        LethalTrifectaDetector.Report moreEgress = report(
            List.of("read_crm"), List.of("fetch_web_page"), List.of("send_mail", "slack_post"));

        assertEquals(Action.BLOCK, TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.BLOCK, LETHAL, moreEgress));
    }

    @Test
    void anUnknownBeforeIsNeverNoWorse() {
        assertFalse(TrifectaEnforcementPolicy.isNoWorse(null, LETHAL));
        assertEquals(Action.BLOCK, TrifectaEnforcementPolicy.decide(TrifectaEnforcementMode.BLOCK, null, LETHAL));
    }

    @Test
    void aNullModeBehavesAsWarn() {
        assertEquals(Action.WARN, TrifectaEnforcementPolicy.decide(null, NONE, LETHAL));
    }

    // ---- block message ----------------------------------------------------

    @Test
    void blockMessageNamesTheThreeLegsAndHowToFixIt() {
        String message = TrifectaEnforcementPolicy.blockMessage(LETHAL);

        assertTrue(message.startsWith(TrifectaEnforcementPolicy.BLOCK_MESSAGE_PREFIX), message);
        assertTrue(message.contains("read_crm"), message);
        assertTrue(message.contains("fetch_web_page"), message);
        assertTrue(message.contains("send_mail"), message);
        assertTrue(message.contains("Remove at least one"), message);
        assertTrue(message.contains("require-approval"), message);
    }

    @Test
    void blockMessageCapsAVeryLongLegList() {
        LethalTrifectaDetector.Report many = report(
            List.of("a", "b", "c", "d", "e", "f", "g"), List.of("u"), List.of("e1"));

        String message = TrifectaEnforcementPolicy.blockMessage(many);

        assertTrue(message.contains("a, b, c, d, e and 2 more"), message);
        assertFalse(message.contains(", f"), message);
    }

    // ---- exempt-list override --------------------------------------------

    @Test
    void exemptionHoldsForAnOrdinaryAgentAndIsRevokedForATrifectaAgent() {
        assertTrue(TrifectaEnforcementPolicy.exemptionHolds(
            TrifectaEnforcementMode.REQUIRE_APPROVAL, true, Boolean.FALSE));
        assertFalse(TrifectaEnforcementPolicy.exemptionHolds(
            TrifectaEnforcementMode.REQUIRE_APPROVAL, true, Boolean.TRUE));
        assertFalse(TrifectaEnforcementPolicy.exemptionHolds(TrifectaEnforcementMode.BLOCK, true, Boolean.TRUE));
    }

    @Test
    void exemptionFailsSafeWhenTheVerdictIsUnknown() {
        assertFalse(TrifectaEnforcementPolicy.exemptionHolds(TrifectaEnforcementMode.REQUIRE_APPROVAL, true, null));
        assertFalse(TrifectaEnforcementPolicy.exemptionHolds(TrifectaEnforcementMode.BLOCK, true, null));
    }

    @Test
    void warnNeverChangesDispatchBecauseItIsTodaysBehaviour() {
        assertTrue(TrifectaEnforcementPolicy.exemptionHolds(TrifectaEnforcementMode.WARN, true, Boolean.TRUE));
        assertTrue(TrifectaEnforcementPolicy.exemptionHolds(TrifectaEnforcementMode.WARN, true, null));
    }

    @Test
    void exemptionIsUntouchedWhenTheOverrideOrEnforcementIsOff() {
        assertTrue(TrifectaEnforcementPolicy.exemptionHolds(TrifectaEnforcementMode.WARN, false, Boolean.TRUE));
        assertTrue(TrifectaEnforcementPolicy.exemptionHolds(TrifectaEnforcementMode.WARN, false, null));
        assertTrue(TrifectaEnforcementPolicy.exemptionHolds(TrifectaEnforcementMode.OFF, true, Boolean.TRUE));
        assertTrue(TrifectaEnforcementPolicy.exemptionHolds(TrifectaEnforcementMode.OFF, true, null));
    }

    // ---- enforced / gated flags ------------------------------------------

    @Test
    void enforcedMeansTheModeActsOnALethalAgent() {
        assertTrue(TrifectaEnforcementPolicy.isEnforced(true, TrifectaEnforcementMode.REQUIRE_APPROVAL));
        assertTrue(TrifectaEnforcementPolicy.isEnforced(true, TrifectaEnforcementMode.BLOCK));
        assertFalse(TrifectaEnforcementPolicy.isEnforced(true, TrifectaEnforcementMode.WARN));
        assertFalse(TrifectaEnforcementPolicy.isEnforced(true, TrifectaEnforcementMode.OFF));
        assertFalse(TrifectaEnforcementPolicy.isEnforced(false, TrifectaEnforcementMode.BLOCK));
    }

    @Test
    void gatedWhenSupervisedOrWhenTheExemptOverrideAppliesUnderTheConfirmationGate() {
        assertTrue(TrifectaEnforcementPolicy.isGated(
            true, TrifectaEnforcementMode.REQUIRE_APPROVAL, AgentAutonomyTier.SUPERVISED, false, false));
        assertTrue(TrifectaEnforcementPolicy.isGated(
            true, TrifectaEnforcementMode.BLOCK, AgentAutonomyTier.SEMI_AUTONOMOUS, true, true));
        assertFalse(TrifectaEnforcementPolicy.isGated(
            true, TrifectaEnforcementMode.BLOCK, AgentAutonomyTier.SEMI_AUTONOMOUS, true, false),
            "without the confirmation gate there is nothing to un-exempt");
        assertFalse(TrifectaEnforcementPolicy.isGated(
            true, TrifectaEnforcementMode.BLOCK, AgentAutonomyTier.SEMI_AUTONOMOUS, false, true));
    }

    @Test
    void neverGatedByEnforcementInWarnMode() {
        assertFalse(TrifectaEnforcementPolicy.isGated(
            true, TrifectaEnforcementMode.WARN, AgentAutonomyTier.SEMI_AUTONOMOUS, true, true));
        assertFalse(TrifectaEnforcementPolicy.isGated(
            true, TrifectaEnforcementMode.WARN, AgentAutonomyTier.SUPERVISED, true, true));
    }

    @Test
    void neverGatedWhenNotLethalOffOrFullyAutonomous() {
        assertFalse(TrifectaEnforcementPolicy.isGated(
            false, TrifectaEnforcementMode.REQUIRE_APPROVAL, AgentAutonomyTier.SUPERVISED, true, true));
        assertFalse(TrifectaEnforcementPolicy.isGated(
            true, TrifectaEnforcementMode.OFF, AgentAutonomyTier.SUPERVISED, true, true));
        assertFalse(TrifectaEnforcementPolicy.isGated(
            true, TrifectaEnforcementMode.REQUIRE_APPROVAL, AgentAutonomyTier.FULLY_AUTONOMOUS, true, true));
    }
}
