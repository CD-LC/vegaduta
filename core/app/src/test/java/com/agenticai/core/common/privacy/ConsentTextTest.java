package com.agenticai.core.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.agenticai.core.common.privacy.ConsentText.Intent;
import com.agenticai.core.common.privacy.ConsentText.Keywords;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsentTextTest {

    private static Intent intent(String message) {
        return ConsentText.parse(message, Keywords.DEFAULTS).intent();
    }

    @Test
    void clearAgreementIsAcceptedWhateverTheCaseAndPunctuation() {
        assertThat(intent("YES")).isEqualTo(Intent.ACCEPT);
        assertThat(intent("  Yes! ")).isEqualTo(Intent.ACCEPT);
        assertThat(intent("I agree.")).isEqualTo(Intent.ACCEPT);
        assertThat(intent("हाँ")).isEqualTo(Intent.ACCEPT);
    }

    @Test
    void ambiguousAcknowledgementsAreNotConsent() {
        // Consent needs a clear affirmative action (Act s.6(1)); these are ordinary chat words.
        assertThat(intent("ok")).isEqualTo(Intent.OTHER);
        assertThat(intent("okay")).isEqualTo(Intent.OTHER);
        assertThat(intent("y")).isEqualTo(Intent.OTHER);
        assertThat(intent("sure, what do you do?")).isEqualTo(Intent.OTHER);
        assertThat(intent("I do not know")).isEqualTo(Intent.OTHER);
        assertThat(intent("")).isEqualTo(Intent.OTHER);
        assertThat(intent(null)).isEqualTo(Intent.OTHER);
    }

    @Test
    void declineAndWithdrawAreRecognised() {
        assertThat(intent("no")).isEqualTo(Intent.DECLINE);
        assertThat(intent("I do not agree")).isEqualTo(Intent.DECLINE);
        assertThat(intent("STOP")).isEqualTo(Intent.WITHDRAW);
        assertThat(intent("withdraw consent")).isEqualTo(Intent.WITHDRAW);
    }

    @Test
    void agreeingInASentenceIsNotAgreement() {
        assertThat(intent("yes but only for support")).isEqualTo(Intent.OTHER);
        assertThat(intent("stop calling me about offers")).isEqualTo(Intent.OTHER);
    }

    @Test
    void languageCommandCarriesTheCode() {
        ConsentText.Parsed parsed = ConsentText.parse("LANG hi", Keywords.DEFAULTS);
        assertThat(parsed.intent()).isEqualTo(Intent.LANGUAGE);
        assertThat(parsed.language()).isEqualTo("hi");
        assertThat(ConsentText.parse("language ta", Keywords.DEFAULTS).language()).isEqualTo("ta");
        assertThat(ConsentText.parse("lang en-in", Keywords.DEFAULTS).language())
                .isEqualTo("en-in");
    }

    @Test
    void customKeywordsReplaceTheBuiltInOnesForThatList() {
        Keywords custom = new Keywords(List.of("I accept"), List.of(), List.of("quit"));
        assertThat(ConsentText.parse("i accept", custom).intent()).isEqualTo(Intent.ACCEPT);
        assertThat(ConsentText.parse("yes", custom).intent()).isEqualTo(Intent.OTHER);
        assertThat(ConsentText.parse("quit", custom).intent()).isEqualTo(Intent.WITHDRAW);
        assertThat(ConsentText.parse("stop", custom).intent()).isEqualTo(Intent.OTHER);
        // decline list was empty: built-ins stay.
        assertThat(ConsentText.parse("no", custom).intent()).isEqualTo(Intent.DECLINE);
    }

    @Test
    void noticeFallsBackToTheDefaultLanguage() {
        Map<String, String> notices = Map.of("en", "English notice", "hi", "Hindi notice");
        assertThat(ConsentText.pick(notices, "hi", "en")).isEqualTo("Hindi notice");
        assertThat(ConsentText.pick(notices, "ta", "en")).isEqualTo("English notice");
        assertThat(ConsentText.pick(notices, null, "en")).isEqualTo("English notice");
    }

    @Test
    void composedNoticeAlwaysTellsTheContactHowToAnswerAndWithdraw() {
        String text = ConsentText.compose("We process your name.", null);
        assertThat(text).startsWith("We process your name.").contains("YES").contains("STOP");
        assertThat(ConsentText.compose("N", "Custom how-to")).endsWith("Custom how-to");
    }

    @Test
    void hashChangesWhenTheNoticeChanges() {
        assertThat(ConsentText.sha256("a")).hasSize(64).isNotEqualTo(ConsentText.sha256("b"));
        assertThat(ConsentText.sha256("a")).isEqualTo(ConsentText.sha256("a"));
    }

    @Test
    void aVeryLongRunOfPunctuationIsHandledInBoundedTime() {
        // Inbound channel text is unbounded: the trailing-punctuation pattern was quadratic on
        // this.
        String hostile = "a" + "!".repeat(50_000) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ConsentText.normalize(hostile));
        assertTimeoutPreemptively(
                Duration.ofSeconds(2), () -> ConsentText.parse(hostile, Keywords.DEFAULTS));
        assertThat(intent(hostile)).isEqualTo(Intent.OTHER);
    }

    @Test
    void aLongMessageIsNeverReadAsConsentByCuttingItShort() {
        // "yes", then padding, then more text: truncating to the cap would leave a bare "yes".
        String padded =
                "yes"
                        + " ".repeat(ConsentText.MAX_NORMALIZED_CHARS + 10)
                        + "but only if you delete everything.";
        assertThat(intent(padded)).isEqualTo(Intent.OTHER);
        assertThat(intent("yes" + " ".repeat(2_000)))
                .isEqualTo(Intent.ACCEPT); // trailing blanks are trimmed first
    }

    /** The pre-cap implementation of {@link ConsentText#normalize}, verbatim. */
    private static String normalizeBeforeTheCap(String message) {
        String s = message.trim().toLowerCase(java.util.Locale.ROOT);
        s = s.replaceAll("[\\p{Punct}।!]+$", "").trim();
        return s.replaceAll("\\s+", " ");
    }

    @Test
    void ordinaryRepliesAreNormalisedExactlyAsBeforeTheCap() {
        String[] replies = {
            "YES",
            "  Yes! ",
            "I agree.",
            "I AGREE!!!",
            "हाँ।",
            "सहमत !",
            "no...",
            "Nahi?!",
            "STOP.",
            "opt   out",
            "withdraw\tconsent\n",
            "LANG hi",
            "language   ta.",
            "lang en-in!",
            "yes but only for support.",
            "stop calling me about offers!!",
            "  ok  ",
            "",
            "   ",
            "?!.,",
            "A longer ordinary question: can you tell me, in a few words, what you do with my"
                    + " name and phone number, and for how long you keep them?"
        };
        for (String reply : replies) {
            assertThat(ConsentText.normalize(reply))
                    .as("reply %s", reply)
                    .isEqualTo(normalizeBeforeTheCap(reply));
        }
        assertThat(ConsentText.normalize(null)).isEmpty();
        // Exactly at the cap is still cleaned up the old way; one past it is returned as read.
        String atCap = "yes" + "!".repeat(ConsentText.MAX_NORMALIZED_CHARS - 3);
        assertThat(atCap).hasSize(ConsentText.MAX_NORMALIZED_CHARS);
        assertThat(ConsentText.normalize(atCap)).isEqualTo(normalizeBeforeTheCap(atCap));
        assertThat(intent(atCap)).isEqualTo(Intent.ACCEPT);
    }

    @Test
    void everyBuiltInKeywordStillParsesWithTheCap() {
        assertThat(intent("Yes.")).isEqualTo(Intent.ACCEPT);
        assertThat(intent("No!")).isEqualTo(Intent.DECLINE);
        assertThat(intent("Unsubscribe")).isEqualTo(Intent.WITHDRAW);
        assertThat(intent("opt out")).isEqualTo(Intent.WITHDRAW);
        assertThat(intent("I   consent")).isEqualTo(Intent.ACCEPT);
    }
}
