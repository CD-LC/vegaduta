package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * The machine-readable markers the tool loop reads out of tool responses: detection is a content
 * match on {@code "error":"<marker>"} inside the JSON tool result, so it survives the
 * {@code {"result":...}} wrap non-object outputs get. The same strings are duplicated in
 * core/guardrails, which a separate test pins.
 */
class ToolLoopMarkersTest {

    @Test
    void aResponseCarryingTheBudgetMarkerIsDetected() {
        ToolResponseMessage message = message(
            "{\"error\":\"" + ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED
                + "\",\"result\":\"stop\"}");

        assertTrue(ToolLoopMarkers.carriesBudgetMarker(message));
        assertFalse(ToolLoopMarkers.carriesParkedMarker(message));
    }

    @Test
    void aResponseCarryingTheParkedMarkerIsDetected() {
        ToolResponseMessage message = message(
            "{\"error\":\"" + ArgumentSanitizingToolCallingManager.APPROVAL_PARKED + "\"}");

        assertTrue(ToolLoopMarkers.carriesParkedMarker(message));
        assertFalse(ToolLoopMarkers.carriesBudgetMarker(message));
    }

    @Test
    void plainResultsCarryNeitherMarker() {
        ToolResponseMessage message = message("{\"result\":\"all good\"}");

        assertFalse(ToolLoopMarkers.carriesBudgetMarker(message));
        assertFalse(ToolLoopMarkers.carriesParkedMarker(message));
    }

    @Test
    void aMarkerInAnyOneResponseOfManyIsFound() {
        ToolResponseMessage message = ToolResponseMessage.builder()
            .responses(List.of(
                new ToolResponseMessage.ToolResponse("1", "t", "{\"result\":\"ok\"}"),
                new ToolResponseMessage.ToolResponse("2", "t",
                    "{\"error\":\"" + ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED + "\"}")))
            .build();

        assertTrue(ToolLoopMarkers.carriesBudgetMarker(message));
    }

    @Test
    void aNullResponseDataCarriesNothing() {
        ToolResponseMessage message = ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse("1", "t", null)))
            .build();

        assertFalse(ToolLoopMarkers.carriesBudgetMarker(message));
        assertFalse(ToolLoopMarkers.carriesParkedMarker(message));
    }

    @Test
    void theFallbackSoftStopIsJsonThatItselfCarriesTheBudgetMarker() {
        String fallback = ToolLoopMarkers.fallbackSoftStop("answer now, with \"quotes\" in it");

        ToolResponseMessage message = message(fallback);
        assertTrue(ToolLoopMarkers.carriesBudgetMarker(message));
        assertTrue(fallback.contains("answer now"), fallback);
    }

    private static ToolResponseMessage message(String data) {
        return ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse("id", "tool", data)))
            .build();
    }
}
