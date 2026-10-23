package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agenticai.core.chat.AgentTurn.Message;
import com.agenticai.core.chat.AgentTurn.Tool;
import com.agenticai.core.chat.AgentTurn.ToolCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The request bounds of {@code POST /api/completions/agent-turn} are settings ({@code
 * app.agent-turn.limits.*}), not constants: the controller enforces whatever the properties say.
 */
class AgentTurnLimitsTest {

    private static AgentTurn.Request withTools(int tools) {
        List<Tool> list = new ArrayList<>();
        for (int i = 0; i < tools; i++) {
            list.add(new Tool("tool_" + i, "d", Map.of()));
        }
        return new AgentTurn.Request(List.of(new Message("user", "hi", null, null, null)), list, null, null, null);
    }

    private static HttpStatus statusOf(AgentTurn.Request request, AgentTurnLimitsProperties limits) {
        ResponseStatusException e =
            assertThrows(ResponseStatusException.class, () -> AgentTurnController.validate(request, limits));
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    @Test
    void theShippedDefaultsAreTheLimitsTheEndpointAlwaysHad() {
        AgentTurnLimitsProperties limits = new AgentTurnLimitsProperties();
        assertEquals(400, limits.getMaxMessages());
        assertEquals(600_000, limits.getMaxTotalChars());
        assertEquals(40, limits.getMaxTools());
        assertEquals(32, limits.getMaxToolCallsPerMessage());
    }

    @Test
    void aLoweredToolLimitRejectsWhatTheDefaultAccepts() {
        AgentTurnLimitsProperties limits = new AgentTurnLimitsProperties();
        assertDoesNotThrow(() -> AgentTurnController.validate(withTools(10), limits));
        limits.setMaxTools(5);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(withTools(10), limits));
    }

    @Test
    void aRaisedToolLimitAcceptsWhatTheDefaultRejects() {
        AgentTurnLimitsProperties limits = new AgentTurnLimitsProperties();
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(withTools(60), limits));
        limits.setMaxTools(80);
        assertDoesNotThrow(() -> AgentTurnController.validate(withTools(60), limits));
    }

    @Test
    void theMessageAndSizeLimitsFollowTheProperties() {
        AgentTurnLimitsProperties limits = new AgentTurnLimitsProperties();
        limits.setMaxMessages(2);
        AgentTurn.Request three = new AgentTurn.Request(List.of(
            new Message("user", "a", null, null, null),
            new Message("assistant", "b", null, null, null),
            new Message("user", "c", null, null, null)), null, null, null, null);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(three, limits));

        AgentTurnLimitsProperties small = new AgentTurnLimitsProperties();
        small.setMaxTotalChars(10);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, statusOf(new AgentTurn.Request(
            List.of(new Message("user", "x".repeat(11), null, null, null)), null, null, null, null), small));
    }

    @Test
    void theCallsPerMessageLimitFollowsTheProperty() {
        AgentTurnLimitsProperties limits = new AgentTurnLimitsProperties();
        limits.setMaxToolCallsPerMessage(1);
        AgentTurn.Request request = new AgentTurn.Request(List.of(
            new Message("user", "go", null, null, null),
            new Message("assistant", "", List.of(new ToolCall("1", "a", Map.of()), new ToolCall("2", "b", Map.of())),
                null, null),
            new Message("tool", "o", null, "1", "a"),
            new Message("tool", "o", null, "2", "b")), null, null, null, null);
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(request, limits));
        limits.setMaxToolCallsPerMessage(2);
        assertDoesNotThrow(() -> AgentTurnController.validate(request, limits));
    }
}
