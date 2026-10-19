package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class SystemMessageMergingAdvisorTest {

    private static ChatClientRequest requestOf(Message... messages) {
        return new ChatClientRequest(new Prompt(List.of(messages)), Map.of());
    }

    private static List<Message> resultMessages(ChatClientRequest request) {
        return SystemMessageMergingAdvisor.mergeSystemMessages(request).prompt().getInstructions();
    }

    @Test
    void leavesRequestWithNoSystemMessageUntouched() {
        ChatClientRequest request = requestOf(new UserMessage("hello"));
        // Strict no-op: the very same instance comes back.
        assertSame(request, SystemMessageMergingAdvisor.mergeSystemMessages(request));
    }

    @Test
    void leavesRequestWithSingleSystemMessageUntouched() {
        ChatClientRequest request = requestOf(new SystemMessage("only one"), new UserMessage("hi"));
        assertSame(request, SystemMessageMergingAdvisor.mergeSystemMessages(request));
    }

    @Test
    void mergesMultipleSystemMessagesIntoOnePreservingOrder() {
        ChatClientRequest request = requestOf(
            new SystemMessage("base instructions"),
            new SystemMessage("memory block"),
            new UserMessage("what's up"),
            new SystemMessage("plan block"));

        List<Message> result = resultMessages(request);

        // Exactly one system message survives, and the non-system message stays.
        assertEquals(2, result.size());
        Message first = result.get(0);
        assertInstanceOf(SystemMessage.class, first);
        assertEquals(
            "base instructions" + SystemMessageMergingAdvisor.SEPARATOR
                + "memory block" + SystemMessageMergingAdvisor.SEPARATOR
                + "plan block",
            ((SystemMessage) first).getText());
        // The merged block sits at the position of the FIRST system message,
        // and the user message keeps its relative order after it.
        assertInstanceOf(UserMessage.class, result.get(1));
        assertEquals("what's up", ((UserMessage) result.get(1)).getText());
    }

    @Test
    void skipsBlankSystemMessagesWhenMerging() {
        ChatClientRequest request = requestOf(
            new SystemMessage("real one"),
            new SystemMessage("   "),
            new UserMessage("go"));

        List<Message> result = resultMessages(request);

        assertEquals(2, result.size());
        assertEquals("real one", ((SystemMessage) result.get(0)).getText());
    }

    @Test
    void keepsAssistantAndUserMessagesInPlace() {
        ChatClientRequest request = requestOf(
            new SystemMessage("sys a"),
            new UserMessage("u1"),
            new AssistantMessage("a1"),
            new SystemMessage("sys b"),
            new UserMessage("u2"));

        List<Message> result = resultMessages(request);

        assertEquals(4, result.size());
        assertInstanceOf(SystemMessage.class, result.get(0));
        assertEquals("sys a" + SystemMessageMergingAdvisor.SEPARATOR + "sys b",
            ((SystemMessage) result.get(0)).getText());
        assertInstanceOf(UserMessage.class, result.get(1));
        assertInstanceOf(AssistantMessage.class, result.get(2));
        assertInstanceOf(UserMessage.class, result.get(3));
    }
}
