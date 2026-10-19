package com.agenticai.core.agents.learning.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PreferenceCatalogTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "make it shorter|short-replies",
                "That was too long, be concise|short-replies",
                "Keep it brief please|short-replies",
                "can you give me more detail on that|detailed-replies",
                "please no emojis|no-emojis",
                "Don't use emojis in your replies|no-emojis",
                "stop using emojis|no-emojis",
                "answer in bullet points|bullet-points",
                "skip the intro and get to the point|answer-first",
                "use a more formal tone|formal-tone",
                "be more casual|casual-tone",
                "reply in Hindi from now on|language:hindi",
                "Always respond in Kannada|language:kannada",
                "can you answer in english|language:english"
            })
    void recognizesStyleRequests(String message, String key) {
        assertTrue(PreferenceCatalog.detect(message).containsKey(key), message);
    }

    @Test
    void oneMessageCanCarryTwoSignals() {
        assertEquals(
                Set.of("short-replies", "no-emojis"),
                PreferenceCatalog.detect("shorter please, and no emojis").keySet());
    }

    @Test
    void theLanguageNameBecomesThePhrase() {
        assertEquals(
                "Prefers replies in Hindi",
                PreferenceCatalog.detect("reply in Hindi").get("language:hindi").phrase());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "don't make it shorter",
                "it is not too long",
                "never be more concise than this",
                "what is the capital of France",
                "reply in a minute",
                "write in my notebook",
                "tell me about emojis",
                ""
            })
    void ordinaryOrNegatedMessagesAreNotSignals(String message) {
        assertTrue(PreferenceCatalog.detect(message).isEmpty(), message);
    }

    @Test
    void aLongMessageIsADocumentNotAnInstruction() {
        String longMessage = "make it shorter. " + "x".repeat(400);
        assertTrue(PreferenceCatalog.detect(longMessage).isEmpty());
        assertTrue(PreferenceCatalog.detect(null).isEmpty());
    }

    @Test
    void curlyApostropheNegationIsRecognized() {
        assertTrue(PreferenceCatalog.detect("don’t make it shorter").isEmpty());
    }
}
