package com.agenticai.core.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.common.privacy.ConsentText.Intent;
import com.agenticai.core.common.privacy.ConsentText.Keywords;
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
        assertThat(ConsentText.parse("lang en-in", Keywords.DEFAULTS).language()).isEqualTo("en-in");
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
}
