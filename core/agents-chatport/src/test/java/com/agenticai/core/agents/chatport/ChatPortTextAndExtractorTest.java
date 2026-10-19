package com.agenticai.core.agents.chatport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatPortTextAndExtractorTest {

    // ---- ChatPortText ----

    @Test
    void cleanDropsControlBidiAndTagCharactersButKeepsTextAndEmoji() {
        String dirty = "a\u0000b\u202Ec\u200Bd\uFEFFe\r\nf\rg\th\n" + new String(Character.toChars(0xE0041)) + "👍";
        assertEquals("abcde\nf\ng\th\n👍", ChatPortText.clean(dirty));
        assertEquals("", ChatPortText.clean(null));
    }

    @Test
    void oneLineCollapsesAllWhitespace() {
        assertEquals("a b c", ChatPortText.oneLine("  a \n\n b\t\tc  "));
        assertEquals("", ChatPortText.oneLine("   "));
    }

    @Test
    void truncateCutsOnACodePointBoundary() {
        assertEquals("abc", ChatPortText.truncate("abc", 5, true));
        assertEquals("ab…", ChatPortText.truncate("abcdef", 2, true));
        assertEquals("ab", ChatPortText.truncate("abcdef", 2, false));
        // 2 chars of budget lands in the middle of a surrogate pair: the half pair is dropped, never emitted
        String emoji = "a👍b";
        assertEquals("a", ChatPortText.truncate(emoji, 2, false));
        assertEquals("a👍", ChatPortText.truncate(emoji, 3, false));
    }

    // ---- extractor ----

    @Test
    void extractsFirstPersonStatementsAndDropsQuestionsAndNoise() {
        List<String> out = ChatMemoryCandidateExtractor.extract(List.of(
            "I live in Pune. Can you help me? Please write a poem.\nMy name is Asha Rao\nthe sky is blue and I use Linux daily.",
            "I prefer short answers. I live in Pune.",
            "I'm a backend engineer working on payments.",
            "ok"), 50, 10_000);
        assertEquals(List.of("I live in Pune.", "My name is Asha Rao", "I prefer short answers.",
            "I'm a backend engineer working on payments."), out);
    }

    @Test
    void extractionHonoursTheLineAndCharacterBudgets() {
        List<String> messages = List.of("I live in Pune.", "I work at Acme Corp.", "I use Linux every day.");
        assertEquals(2, ChatMemoryCandidateExtractor.extract(messages, 2, 10_000).size());
        // 16 + 21 > 30: only the first fits
        assertEquals(List.of("I live in Pune."), ChatMemoryCandidateExtractor.extract(messages, 50, 30));
        assertTrue(ChatMemoryCandidateExtractor.extract(null, 5, 100).isEmpty());
        assertTrue(ChatMemoryCandidateExtractor.extract(List.of("", "  "), 5, 100).isEmpty());
    }

    @Test
    void veryLongSentencesAreNotCandidates() {
        String longOne = "I like " + "very ".repeat(100) + "much.";
        assertTrue(ChatMemoryCandidateExtractor.extract(List.of(longOne), 5, 10_000).isEmpty());
    }
}
