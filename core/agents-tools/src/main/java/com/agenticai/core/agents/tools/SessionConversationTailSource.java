package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.guardrails.ConversationTailSource;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The end of a conversation, read from the session memory the turn itself reads its history from, for the
 * RIGOROUS tool-call verifier. Reads the bounded history window (never the unbounded transcript) under the
 * turn's scoped memory id, which already names the tenant, agent and principal, so one tenant's verifier can
 * never be shown another's conversation.
 */
final class SessionConversationTailSource implements ConversationTailSource {

    private static final Logger log = LoggerFactory.getLogger(SessionConversationTailSource.class);

    private final ObjectProvider<SessionMemoryService> sessionMemory;

    SessionConversationTailSource(ObjectProvider<SessionMemoryService> sessionMemory) {
        this.sessionMemory = sessionMemory;
    }

    @Override
    public String tail(String memorySessionId, int maxMessages, int maxChars) {
        if (memorySessionId == null || memorySessionId.isBlank() || maxMessages <= 0 || maxChars <= 0) {
            return "";
        }
        SessionMemoryService memory = sessionMemory == null ? null : sessionMemory.getIfAvailable();
        if (memory == null) {
            return "";
        }
        List<ChatTurn> turns;
        try {
            turns = memory.getHistory(memorySessionId);
        } catch (RuntimeException e) {
            log.warn("Could not read the conversation tail for the tool-call verifier", e);
            return "";
        }
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        return format(turns, maxMessages, maxChars);
    }

    /** The newest {@code maxMessages} turns, oldest first, the OLDEST text dropped first to fit {@code maxChars}. */
    static String format(List<ChatTurn> turns, int maxMessages, int maxChars) {
        List<String> lines = new ArrayList<>();
        int used = 0;
        for (int i = turns.size() - 1; i >= 0 && lines.size() < maxMessages; i--) {
            ChatTurn turn = turns.get(i);
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String line = (turn.role() == MessageRole.USER ? "User: " : "Assistant: ") + turn.content().strip();
            int room = maxChars - used - (lines.isEmpty() ? 0 : 1);
            if (room <= 0) {
                break;
            }
            if (line.length() > room) {
                // Keep the END of the message that does not fit: it is the part closest to the call.
                lines.add(line.substring(line.length() - room));
                used += room;
                break;
            }
            lines.add(line);
            used += line.length() + (lines.size() > 1 ? 1 : 0);
        }
        java.util.Collections.reverse(lines);
        return String.join("\n", lines);
    }
}
