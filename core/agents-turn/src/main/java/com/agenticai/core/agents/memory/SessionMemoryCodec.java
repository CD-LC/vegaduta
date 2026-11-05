package com.agenticai.core.agents.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChatTurn JSON (de)serialization for {@link SessionMemoryService}, including the summary-facing
 * tool-record fold. Extracted so the service stays under the file-size cap. Package-private.
 */
final class SessionMemoryCodec {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryCodec.class);

    private final SessionMemoryService owner;

    SessionMemoryCodec(SessionMemoryService owner) {
        this.owner = owner;
    }

    String serialize(ChatTurn turn) {
        try {
            return owner.objectMapper.writeValueAsString(turn);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat turn", e);
        }
    }

    /**
     * A stored turn as the rolling summary reads it: the tool record folded into the text, so what
     * a turn did with its tools survives into the summary instead of leaving with the turn.
     */
    ChatTurn deserializeForSummary(String json) {
        ChatTurn turn = deserialize(json);
        if (turn == null || turn.tools() == null) {
            return turn;
        }
        return ToolLedgerReplay.modelView(turn, owner.toolLedger(), true).orElse(turn);
    }

    ChatTurn deserialize(String json) {
        try {
            return owner.objectMapper.readValue(json, ChatTurn.class);
        } catch (JsonProcessingException e) {
            // A corrupt/unreadable entry shouldn't take down the whole conversation history.
            log.warn("Skipping unreadable chat turn in session history", e);
            return null;
        }
    }
}
