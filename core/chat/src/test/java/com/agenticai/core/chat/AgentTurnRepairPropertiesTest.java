package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code app.agent-turn.repair.*}: what the hosted agent-turn endpoint spends and says when a
 * tool call's arguments arrive unusable. Defaults ship in {@code /agent-turn/repair.properties};
 * the model-facing texts carry {@code {tool}}/{@code {reason}} placeholders filled by
 * {@link AgentTurnRepairProperties#render}.
 */
class AgentTurnRepairPropertiesTest {

    @Test
    void theShippedDefaultsAreTheOnesInThePropertiesFile() {
        AgentTurnRepairProperties p = new AgentTurnRepairProperties();

        assertEquals(1, p.getMaxAttempts());
        assertTrue(p.getInvalidArgumentsMessage().contains("{tool}"));
        assertTrue(p.getInvalidArgumentsMessage().contains("{reason}"));
        assertTrue(p.getSkippedMessage().contains("{tool}"));
        assertTrue(p.getInvalidReasonCutOff().contains("output length"));
        assertTrue(p.getInvalidReasonMalformed().contains("JSON"));
        assertTrue(p.getUnusableCallsNotice().contains("{tool}"));
        assertTrue(p.getUnusableCallsNotice().contains("{reason}"));
    }

    @Test
    void maxAttemptsClampsAtZeroSoItNeverAsksAgain() {
        AgentTurnRepairProperties p = new AgentTurnRepairProperties();
        p.setMaxAttempts(-3);
        assertEquals(0, p.getMaxAttempts());

        p.setMaxAttempts(4);
        assertEquals(4, p.getMaxAttempts());
    }

    @Test
    void renderFillsBothPlaceholdersAndLeavesNoneBehind() {
        AgentTurnRepairProperties p = new AgentTurnRepairProperties();

        String rendered = p.render("Call {tool} failed: {reason}.", "get_weather", "not valid JSON");

        assertEquals("Call get_weather failed: not valid JSON.", rendered);
    }

    @Test
    void renderStringifiesNullPartsRatherThanDroppingThem() {
        AgentTurnRepairProperties p = new AgentTurnRepairProperties();

        assertEquals("t=null r=null", p.render("t={tool} r={reason}", null, null));
    }

    @Test
    void everySetterRoundTrips() {
        AgentTurnRepairProperties p = new AgentTurnRepairProperties();
        p.setInvalidArgumentsMessage("invalid {tool} {reason}");
        p.setSkippedMessage("skipped {tool}");
        p.setInvalidReasonCutOff("cut");
        p.setInvalidReasonMalformed("malformed");
        p.setUnusableCallsNotice("unusable {tool} {reason}");

        assertEquals("invalid {tool} {reason}", p.getInvalidArgumentsMessage());
        assertEquals("skipped {tool}", p.getSkippedMessage());
        assertEquals("cut", p.getInvalidReasonCutOff());
        assertEquals("malformed", p.getInvalidReasonMalformed());
        assertEquals("unusable {tool} {reason}", p.getUnusableCallsNotice());
    }
}
