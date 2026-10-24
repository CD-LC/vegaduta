package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The conversation part of a response-cache key ({@code app.chat.cache.*}). */
class ChatCachePolicyTest {

    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");
    private final ChatCacheProperties props = new ChatCacheProperties();

    private static ChatTurn user(String s) {
        return new ChatTurn(MessageRole.USER, s, T);
    }

    private static ChatTurn assistant(String s) {
        return new ChatTurn(MessageRole.ASSISTANT, s, T);
    }

    @Test
    void nothingBeforeTheMessageMeansNoConversationKey() {
        ChatCachePolicy policy = new ChatCachePolicy(props);

        assertEquals("", policy.conversationKey(List.of()));
        assertEquals("", policy.conversationKey(null));
    }

    @Test
    void theKeyFollowsTheLatestTurnsNotTheWholeHistory() {
        props.setContextTurns(2);
        ChatCachePolicy policy = new ChatCachePolicy(props);
        String a = policy.conversationKey(List.of(user("old"), user("q"), assistant("a")));
        String b = policy.conversationKey(List.of(user("different old"), user("q"), assistant("a")));
        String c = policy.conversationKey(List.of(user("q"), assistant("another answer")));

        assertEquals(a, b, "older turns are outside the window");
        assertNotEquals(a, c);
        assertTrue(a.length() == 64, a);
    }

    @Test
    void caseAndWhitespaceDoNotSplitTheKeyAndRoleDoes() {
        ChatCachePolicy policy = new ChatCachePolicy(props);

        assertEquals(
                policy.conversationKey(List.of(user("Shall I  cancel?"))),
                policy.conversationKey(List.of(user("shall i cancel?"))));
        assertNotEquals(
                policy.conversationKey(List.of(user("same text"))),
                policy.conversationKey(List.of(assistant("same text"))));
    }

    @Test
    void switchedOffTheKeyIsAlwaysEmpty() {
        props.setKeyOnConversation(false);

        assertEquals("", new ChatCachePolicy(props).conversationKey(List.of(user("q"), assistant("a"))));
    }
}
