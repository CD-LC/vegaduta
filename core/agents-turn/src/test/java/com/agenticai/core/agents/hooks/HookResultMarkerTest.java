package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.guardrails.ToolResultMarker;
import org.junit.jupiter.api.Test;

/** A hook that stops a tool, or withholds what it returned, answers in place of the tool and says so by type. */
class HookResultMarkerTest {

    @Test
    void aHookDenialIsBlocked() {
        String denied = HookEngine.toolDenied("send_email", "outside business hours");

        assertEquals(ToolResultMarker.Kind.BLOCKED, ToolResultMarker.kindOf(denied).orElseThrow());
    }

    @Test
    void aHookWithheldResultIsWithheld() {
        String withheld = HookEngine.withheldResult("send_email", "contains a card number");

        assertEquals(ToolResultMarker.Kind.WITHHELD, ToolResultMarker.kindOf(withheld).orElseThrow());
    }
}
