package com.agenticai.core.agents.chatport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ImportedChat} is a read-only transcript record: the constructor stamps {@code importedAt},
 * defensively copies the message list (a caller mutating its list afterwards must not rewrite the
 * row), and {@code getMessages} never answers null.
 */
class ImportedChatTest {

    private static ImportedChat chat(List<Map<String, Object>> messages) {
        return new ImportedChat(
            "alice@example.com", "chatgpt", "conv-1", "Trip planning",
            Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"),
            3, true, messages);
    }

    @Test
    void theConstructorStampsImportTimeAndCarriesEveryField() {
        Instant before = Instant.now();
        List<Map<String, Object>> messages = List.of(
            Map.of("role", "user", "content", "hi"),
            Map.of("role", "assistant", "content", "hello"));

        ImportedChat chat = chat(messages);

        assertEquals("alice@example.com", chat.getOwnerSubject());
        assertEquals("chatgpt", chat.getSource());
        assertEquals("conv-1", chat.getSourceId());
        assertEquals("Trip planning", chat.getTitle());
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), chat.getSourceCreatedAt());
        assertEquals(Instant.parse("2024-01-02T00:00:00Z"), chat.getSourceUpdatedAt());
        assertTrue(!chat.getImportedAt().isBefore(before));
        assertEquals(3, chat.getMessageCount());
        assertTrue(chat.isTruncated());
        assertEquals(2, chat.getMessages().size());
        assertEquals("hi", chat.getMessages().get(0).get("content"));
    }

    @Test
    void aNullMessageListBecomesEmptyNotNull() {
        ImportedChat chat = chat(null);

        assertNotNull(chat.getMessages());
        assertTrue(chat.getMessages().isEmpty());
    }

    @Test
    void theStoredListIsACopyOfTheCallers() {
        List<Map<String, Object>> messages = new ArrayList<>(List.of(Map.of("role", "user")));

        ImportedChat chat = chat(messages);
        messages.add(Map.of("role", "assistant"));

        assertEquals(1, chat.getMessages().size());
    }

    @Test
    void getMessagesIsNeverNullEvenOnAnUnreadRow() throws Exception {
        // The JPA no-arg path: a hydrated row could leave the field null; the getter still answers.
        var ctor = ImportedChat.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ImportedChat chat = ctor.newInstance();

        assertNotNull(chat.getMessages());
        assertTrue(chat.getMessages().isEmpty());
        assertFalse(chat.isTruncated());
    }

    @Test
    void idsAreNullUntilPersisted() {
        assertNotEquals(null, chat(List.of())); // sanity: the entity itself exists
        assertEquals(null, chat(List.of()).getId());
    }
}
