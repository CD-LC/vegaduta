package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TurnContinuationTest {

    @Test
    void announcedButUndoneStepIsUnfinished() {
        assertTrue(TurnContinuation.looksUnfinished("Good. Let me create the pom.xml file:"));
        assertTrue(
                TurnContinuation.looksUnfinished(
                        "I will check what files exist and then create the next files."));
    }

    @Test
    void questionsSummariesAndApprovalWaitsAreNotUnfinished() {
        assertFalse(TurnContinuation.looksUnfinished("Which database should I use?"));
        assertFalse(TurnContinuation.looksUnfinished("All done. The project builds and you can now run it."));
        assertFalse(TurnContinuation.looksUnfinished("I'll wait: the shell command needs your approval."));
        assertFalse(TurnContinuation.looksUnfinished("   "));
        assertFalse(TurnContinuation.looksUnfinished(null));
    }
}
