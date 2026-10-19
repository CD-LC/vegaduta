package com.agenticai.core.sarvam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SarvamIndicLanguagesTest {

    @Test
    void normalizeAcceptsBareUnderscoreAndMixedCaseForms() {
        assertEquals("hi-IN", SarvamIndicLanguages.normalize("hi"));
        assertEquals("hi-IN", SarvamIndicLanguages.normalize("hi_IN"));
        assertEquals("hi-IN", SarvamIndicLanguages.normalize(" HI-in "));
        assertEquals("ta-IN", SarvamIndicLanguages.normalize("ta"));
    }

    @Test
    void normalizeMapsIsoOdiaToSarvamsOdCode() {
        assertEquals("od-IN", SarvamIndicLanguages.normalize("or"));
        assertEquals("od-IN", SarvamIndicLanguages.normalize("or-IN"));
    }

    @Test
    void normalizeReturnsNullForNothingUsable() {
        assertNull(SarvamIndicLanguages.normalize(null));
        assertNull(SarvamIndicLanguages.normalize(""));
        assertNull(SarvamIndicLanguages.normalize("   "));
        assertNull(SarvamIndicLanguages.normalize("unknown"));
        assertNull(SarvamIndicLanguages.normalize("UNKNOWN"));
        assertNull(SarvamIndicLanguages.normalize("hindi"));
    }

    @Test
    void normalizeKeepsOtherRegionsAndUnservedLanguages() {
        assertEquals("en-US", SarvamIndicLanguages.normalize("en-us"));
        assertEquals("ur", SarvamIndicLanguages.normalize("ur"));
        assertEquals("kok-IN", SarvamIndicLanguages.normalize("kok-in"));
    }

    @Test
    void ttsSupportsExactlyTheElevenDocumentedLanguages() {
        assertEquals(11, SarvamIndicLanguages.TTS_LANGUAGES.size());
        for (String code : SarvamIndicLanguages.TTS_LANGUAGES) {
            assertEquals(Optional.of(code), SarvamIndicLanguages.ttsLanguageFor(code), code);
        }
    }

    @Test
    void ttsLanguageForMapsEnglishVariantsAndBareCodes() {
        assertEquals(Optional.of("en-IN"), SarvamIndicLanguages.ttsLanguageFor("en-US"));
        assertEquals(Optional.of("hi-IN"), SarvamIndicLanguages.ttsLanguageFor("hi"));
        assertEquals(Optional.of("od-IN"), SarvamIndicLanguages.ttsLanguageFor("or"));
    }

    @Test
    void ttsLanguageForIsEmptyWhereSarvamHasNoVoice() {
        // Urdu, Nepali, Assamese, Konkani etc. transcribe fine but cannot be spoken back.
        assertTrue(SarvamIndicLanguages.ttsLanguageFor("ur-IN").isEmpty());
        assertTrue(SarvamIndicLanguages.ttsLanguageFor("ne-IN").isEmpty());
        assertTrue(SarvamIndicLanguages.ttsLanguageFor("kok-IN").isEmpty());
        assertTrue(SarvamIndicLanguages.ttsLanguageFor(null).isEmpty());
        assertTrue(SarvamIndicLanguages.ttsLanguageFor("unknown").isEmpty());
        assertFalse(SarvamIndicLanguages.ttsLanguageFor("fr-FR").isPresent());
    }

    @Test
    void displayNameKnowsTheServedLanguagesOnly() {
        assertEquals("Hindi", SarvamIndicLanguages.displayName("hi-IN"));
        assertEquals("Telugu", SarvamIndicLanguages.displayName("te"));
        assertEquals("English", SarvamIndicLanguages.displayName("en-US"));
        assertNull(SarvamIndicLanguages.displayName("ur-IN"));
        assertNull(SarvamIndicLanguages.displayName(null));
    }
}
