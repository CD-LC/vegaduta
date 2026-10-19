package com.agenticai.core.omnichannel.whatsapp.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenEndedAssistantCheckTest {

    @Test
    void emptyPromptIsOpenEnded() {
        assertTrue(OpenEndedAssistantCheck.assess(null, Set.of()).openEnded());
        assertTrue(OpenEndedAssistantCheck.assess("You are helpful.", Set.of()).openEnded());
    }

    @Test
    void generalPurposeAssistantIsOpenEnded() {
        var a =
                OpenEndedAssistantCheck.assess(
                        "You are a general-purpose AI assistant. Answer any question the user has"
                                + " about any topic, and help with customer questions too.",
                        Set.of());
        assertTrue(a.openEnded());
        assertFalse(a.reasons().isEmpty());
    }

    @Test
    void taskSpecificPromptWithScopeLimitPasses() {
        var a =
                OpenEndedAssistantCheck.assess(
                        "You are the front desk for a dental clinic. Help customers book and"
                                + " reschedule appointments. Only answer questions about the clinic;"
                                + " politely decline anything else.",
                        Set.of("calendar_lookup"));
        assertFalse(a.openEnded(), a.reasons().toString());
    }

    @Test
    void taskWordsAloneAreEnoughWhenNothingSaysOtherwise() {
        assertFalse(
                OpenEndedAssistantCheck.assess(
                                "Handle customer support questions about order status for our shop.",
                                Set.of())
                        .openEnded());
    }

    @Test
    void openPhraseWithoutLimiterIsOpenEndedEvenWithTaskWords() {
        assertTrue(
                OpenEndedAssistantCheck.assess(
                                "You handle customer support, and you are a versatile assistant who"
                                        + " can talk about any topic.",
                                Set.of())
                        .openEnded());
    }

    @Test
    void broadToolkitWithoutLimiterIsFlagged() {
        var a =
                OpenEndedAssistantCheck.assess(
                        "Support agent for our order desk and customers.",
                        List.of("web_search", "browse_page", "run_code"));
        assertTrue(a.openEnded());
    }

    @Test
    void broadToolkitWithLimiterIsAccepted() {
        assertFalse(
                OpenEndedAssistantCheck.assess(
                                "Support agent for our order desk. Stay on topic and decline"
                                        + " questions unrelated to orders.",
                                List.of("web_search", "browse_page", "run_code"))
                        .openEnded());
    }
}
