package com.agenticai.core.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * One test per transformation rule in {@link SpeechTextNormalizer}'s javadoc,
 * plus the two properties the live bug actually turns on: nothing that reaches
 * a synthesizer may still contain markdown punctuation, and nothing in an
 * Indic-script reply may be destroyed on the way there.
 */
// S2187: every test lives in a @Nested class below; Sonar only descends into @Nested
// when junit-jupiter-api is on its analysis classpath.
@SuppressWarnings("java:S2187")
class SpeechTextNormalizerTest {

    /** Characters that, left in the string, are what the user hears read out loud. */
    private static void assertNoSpokenMarkup(String normalized) {
        for (char c : new char[] {'*', '`', '~', '|', '\\'}) {
            assertFalse(normalized.indexOf(c) >= 0,
                "normalized text still contains '" + c + "' (the engine will pronounce it): " + normalized);
        }
    }

    @Nested
    @DisplayName("null / empty / never-empty contract")
    class NullSafety {

        @Test
        @DisplayName("null in, null out")
        void nullIsNull() {
            assertNull(SpeechTextNormalizer.normalizeForSpeech(null));
        }

        @Test
        @DisplayName("blank input is returned untouched (nothing to normalize)")
        void blankIsUntouched() {
            assertEquals("", SpeechTextNormalizer.normalizeForSpeech(""));
            assertEquals("   ", SpeechTextNormalizer.normalizeForSpeech("   "));
        }

        @Test
        @DisplayName("markup-only input falls back to the original rather than returning empty")
        void neverEmptyForNonEmptyInput() {
            // An empty TTS request fails the provider call outright, so a
            // normalization that would empty the string must fall back.
            for (String pureMarkup : new String[] {"**", "``", "🎉🎉", "###", "---"}) {
                String out = SpeechTextNormalizer.normalizeForSpeech(pureMarkup);
                assertFalse(out == null || out.isEmpty(),
                    "normalizing '" + pureMarkup + "' produced an empty TTS request");
            }
        }

        @Test
        @DisplayName("the FRAGMENT normalizer deliberately does the opposite and returns empty")
        void fragmentNormalizerHasNoFallback() {
            // A streaming caller (SpeechStreamNormalizer) can simply not send
            // a piece, so for it the never-empty fallback would be the bug -
            // a token boundary through "**bold**" yields a "**" fragment that
            // must be dropped, not pronounced.
            for (String pureMarkup : new String[] {"**", "``", "🎉🎉", "###", "---"}) {
                assertEquals("", SpeechTextNormalizer.normalizeFragmentForSpeech(pureMarkup),
                    "fragment '" + pureMarkup + "' should have been dropped, not spoken");
            }
            assertNull(SpeechTextNormalizer.normalizeFragmentForSpeech(null));
            assertEquals("", SpeechTextNormalizer.normalizeFragmentForSpeech("   "));
        }

        @Test
        @DisplayName("a fragment with something to say is normalized exactly as a whole message would be")
        void fragmentNormalizerOtherwiseMatches() {
            String markdown = "It **shipped** on `2026-07-29` - see [the order](https://example.com/o/1).";
            assertEquals(
                SpeechTextNormalizer.normalizeForSpeech(markdown),
                SpeechTextNormalizer.normalizeFragmentForSpeech(markdown));
        }
    }

    @Nested
    @DisplayName("markdown")
    class Markdown {

        @Test
        @DisplayName("bold/italic/strikethrough markers are removed, the words survive")
        void emphasis() {
            assertEquals("This is bold text", SpeechTextNormalizer.normalizeForSpeech("This is **bold** text"));
            assertEquals("This is bold text", SpeechTextNormalizer.normalizeForSpeech("This is __bold__ text"));
            assertEquals("This is italic text", SpeechTextNormalizer.normalizeForSpeech("This is *italic* text"));
            assertEquals("This is italic text", SpeechTextNormalizer.normalizeForSpeech("This is _italic_ text"));
            assertEquals("This is gone text", SpeechTextNormalizer.normalizeForSpeech("This is ~~gone~~ text"));
        }

        @Test
        @DisplayName("headings lose their hashes and gain a sentence pause")
        void headings() {
            assertEquals("Getting started. Welcome aboard",
                SpeechTextNormalizer.normalizeForSpeech("## Getting started\n\nWelcome aboard"));
            assertEquals("Closed heading.", SpeechTextNormalizer.normalizeForSpeech("### Closed heading ###"));
        }

        @Test
        @DisplayName("blockquote markers are removed, including nested ones")
        void blockquotes() {
            assertEquals("quoted text", SpeechTextNormalizer.normalizeForSpeech("> quoted text"));
            assertEquals("deeply quoted", SpeechTextNormalizer.normalizeForSpeech("> > deeply quoted"));
        }

        @Test
        @DisplayName("unordered bullets are dropped, ordered numbers are kept")
        void bullets() {
            assertEquals("first. second. third.",
                SpeechTextNormalizer.normalizeForSpeech("- first\n* second\n+ third"));
            assertEquals("1. Do this. 2. Do that.",
                SpeechTextNormalizer.normalizeForSpeech("1. Do this\n2. Do that"));
            assertEquals("Ship it.", SpeechTextNormalizer.normalizeForSpeech("- [x] Ship it"));
        }

        @Test
        @DisplayName("horizontal rules disappear entirely")
        void horizontalRules() {
            assertEquals("Above. Below", SpeechTextNormalizer.normalizeForSpeech("Above\n\n---\n\nBelow"));
            assertEquals("Above. Below", SpeechTextNormalizer.normalizeForSpeech("Above\n\n***\n\nBelow"));
            assertEquals("Above. Below", SpeechTextNormalizer.normalizeForSpeech("Above\n\n___\n\nBelow"));
        }

        @Test
        @DisplayName("tables read as comma-separated phrases and the |---| separator row is dropped")
        void tables() {
            String table = "| Plan | Price |\n|------|-------|\n| Free | 0 |\n| Premium | 999 |";
            String out = SpeechTextNormalizer.normalizeForSpeech(table);
            assertEquals("Plan, Price. Free, 0. Premium, 999.", out);
            assertNoSpokenMarkup(out);
        }

        @Test
        @DisplayName("inline code keeps its content, fenced blocks are announced instead of read")
        void code() {
            assertEquals("Set the agentId field", SpeechTextNormalizer.normalizeForSpeech("Set the `agentId` field"));

            String fenced = "Here you go:\n```java\nSystem.out.println(\"**hi**\");\n```\nThat's it.";
            String out = SpeechTextNormalizer.normalizeForSpeech(fenced);
            assertEquals("Here you go: " + SpeechTextNormalizer.CODE_BLOCK_PHRASE + " That's it.", out);
            assertFalse(out.contains("println"), "the code body must not be read aloud: " + out);
            assertNoSpokenMarkup(out);
        }

        @Test
        @DisplayName("an unterminated fence still does not leak the code body")
        void unterminatedFence() {
            String out = SpeechTextNormalizer.normalizeForSpeech("Result:\n```\nrm -rf /\n");
            assertFalse(out.contains("rm -rf"), out);
            assertTrue(out.contains(SpeechTextNormalizer.CODE_BLOCK_PHRASE.replace(".", "")), out);
        }

        @Test
        @DisplayName("links keep their text and drop the URL; images keep the alt text")
        void linksAndImages() {
            assertEquals("See our docs for more",
                SpeechTextNormalizer.normalizeForSpeech("See [our docs](https://example.com/docs) for more"));
            assertEquals("See our docs for more",
                SpeechTextNormalizer.normalizeForSpeech("See [our docs][docs-ref] for more"));
            assertEquals("A revenue chart shows growth",
                SpeechTextNormalizer.normalizeForSpeech("![A revenue chart](https://x.test/c.png) shows growth"));
        }

        @Test
        @DisplayName("snake_case identifiers are spoken as words, not 'underscore'")
        void snakeCase() {
            assertEquals("The agent id column", SpeechTextNormalizer.normalizeForSpeech("The agent_id column"));
        }
    }

    @Nested
    @DisplayName("HTML")
    class Html {

        @Test
        @DisplayName("tags are stripped and entities decoded")
        void tagsAndEntities() {
            assertEquals("Hello and welcome",
                SpeechTextNormalizer.normalizeForSpeech("<p>Hello &amp; welcome</p>"));
            assertEquals("Bold here",
                SpeechTextNormalizer.normalizeForSpeech("<div class=\"x\"><b>Bold</b> here</div>"));
            assertEquals("non breaking space",
                SpeechTextNormalizer.normalizeForSpeech("non&nbsp;breaking&nbsp;space"));
        }

        @Test
        @DisplayName("numeric entities are decoded")
        void numericEntities() {
            assertEquals("it's fine", SpeechTextNormalizer.normalizeForSpeech("it&#39;s fine"));
            assertEquals("it's fine", SpeechTextNormalizer.normalizeForSpeech("it&#x27;s fine"));
        }

        @Test
        @DisplayName("script/style bodies and comments are never spoken")
        void scriptStyleAndComments() {
            String out = SpeechTextNormalizer.normalizeForSpeech(
                "Before<script>alert('pwned')</script><style>.a{color:red}</style><!-- hidden -->After");
            assertFalse(out.contains("alert"), out);
            assertFalse(out.contains("color"), out);
            assertFalse(out.contains("hidden"), out);
            assertTrue(out.contains("Before"), out);
            assertTrue(out.contains("After"), out);
        }

        @Test
        @DisplayName("an entity-encoded tag cannot re-appear as a literal '<b>'")
        void doubleDecodedTag() {
            String out = SpeechTextNormalizer.normalizeForSpeech("say &lt;b&gt;this&lt;/b&gt; aloud");
            assertEquals("say this aloud", out);
        }
    }

    @Nested
    @DisplayName("URLs and emails")
    class Links {

        @Test
        @DisplayName("a bare URL becomes 'a link' instead of being spelled out")
        void bareUrl() {
            assertEquals("Visit " + SpeechTextNormalizer.LINK_PHRASE + " now",
                SpeechTextNormalizer.normalizeForSpeech("Visit https://example.com/docs/getting-started now"));
            assertEquals("Visit " + SpeechTextNormalizer.LINK_PHRASE + " now",
                SpeechTextNormalizer.normalizeForSpeech("Visit www.example.com now"));
        }

        @Test
        @DisplayName("an autolink becomes 'a link' too")
        void autolink() {
            assertEquals("Try " + SpeechTextNormalizer.LINK_PHRASE + " first",
                SpeechTextNormalizer.normalizeForSpeech("Try <https://example.com> first"));
        }

        @Test
        @DisplayName("a bare email becomes 'an email address'")
        void bareEmail() {
            assertEquals("Mail " + SpeechTextNormalizer.EMAIL_PHRASE + " please",
                SpeechTextNormalizer.normalizeForSpeech("Mail support@example.com please"));
        }
    }

    @Nested
    @DisplayName("emoji, symbols and invisible characters")
    class Symbols {

        @Test
        @DisplayName("emoji and decorative symbols are dropped")
        void emoji() {
            assertEquals("Great job and done",
                SpeechTextNormalizer.normalizeForSpeech("Great job 🎉 and ✅ done"));
            // Skin-tone modifier + variation selector + keycap.
            assertEquals("Nice",
                SpeechTextNormalizer.normalizeForSpeech("Nice 👍🏽️"));
            assertEquals("Step 1", SpeechTextNormalizer.normalizeForSpeech("Step 1️⃣"));
        }

        @Test
        @DisplayName("box drawing and arrows used as decoration are dropped")
        void decoration() {
            assertEquals("input output", SpeechTextNormalizer.normalizeForSpeech("input → output"));
        }

        @Test
        @DisplayName("smart quotes, dashes and ellipsis fold to plain ASCII")
        void smartPunctuation() {
            assertEquals("\"quoted\" and 'it's'",
                SpeechTextNormalizer.normalizeForSpeech("“quoted” and ‘it’s’"));
            assertEquals("wait...", SpeechTextNormalizer.normalizeForSpeech("wait…"));
        }

        @Test
        @DisplayName("ANSI escapes, control characters and zero-width/bidi characters are removed")
        void controlAndInvisible() {
            assertEquals("Hello world!",
                SpeechTextNormalizer.normalizeForSpeech("Hello\u001B[31m world\u200B!"));
            assertEquals("safe text",
                SpeechTextNormalizer.normalizeForSpeech("\u202Esafe\u202C text"));
            assertEquals("bom free",
                SpeechTextNormalizer.normalizeForSpeech("\uFEFFbom free"));
        }
    }

    @Nested
    @DisplayName("whitespace and sentence pauses")
    class Whitespace {

        @Test
        @DisplayName("runs of whitespace collapse to one space")
        void collapse() {
            assertEquals("a b c", SpeechTextNormalizer.normalizeForSpeech("a   b\t\tc"));
        }

        @Test
        @DisplayName("a blank line becomes a full stop so paragraphs don't run together")
        void paragraphBreak() {
            assertEquals("Line one. Line two",
                SpeechTextNormalizer.normalizeForSpeech("Line one\n\n\nLine two"));
        }

        @Test
        @DisplayName("CRLF input is handled the same as LF")
        void crlf() {
            assertEquals("first. second.", SpeechTextNormalizer.normalizeForSpeech("- first\r\n- second"));
        }
    }

    @Nested
    @DisplayName("Unicode / Indic safety")
    class IndicSafety {

        @Test
        @DisplayName("Devanagari text passes through completely untouched")
        void devanagariUntouched() {
            String hindi = "नमस्ते! आपका स्वागत है।";
            assertEquals(hindi, SpeechTextNormalizer.normalizeForSpeech(hindi));
        }

        @Test
        @DisplayName("every major Indic script survives verbatim")
        void allIndicScriptsSurvive() {
            String[] samples = {
                "यह हिंदी है",           // Devanagari
                "இது தமிழ்",             // Tamil
                "ఇది తెలుగు",             // Telugu
                "ಇದು ಕನ್ನಡ",              // Kannada
                "ഇത് മലയാളം",            // Malayalam
                "এটি বাংলা",              // Bengali
                "આ ગુજરાતી છે",           // Gujarati
                "ਇਹ ਪੰਜਾਬੀ ਹੈ",            // Gurmukhi
                "ଏହା ଓଡ଼ିଆ",              // Odia
            };
            for (String sample : samples) {
                assertEquals(sample, SpeechTextNormalizer.normalizeForSpeech(sample),
                    "Indic sample was altered: " + sample);
            }
        }

        @Test
        @DisplayName("markdown around Indic text is stripped without touching the script")
        void markdownAroundIndic() {
            String out = SpeechTextNormalizer.normalizeForSpeech("## नमस्ते\n\n- **मुख्य** बात\n- दूसरी बात");
            assertTrue(out.contains("नमस्ते"), out);
            assertTrue(out.contains("मुख्य बात"), out);
            assertTrue(out.contains("दूसरी बात"), out);
            assertNoSpokenMarkup(out);
        }

        @Test
        @DisplayName("a line already ending in a danda is not given a redundant full stop")
        void dandaIsSentenceEnding() {
            String out = SpeechTextNormalizer.normalizeForSpeech("## हिंदी में उत्तर।");
            assertEquals("हिंदी में उत्तर।", out);
            assertFalse(out.endsWith("।."), out);
        }

        @Test
        @DisplayName("CJK and other non-Latin scripts are not stripped either")
        void otherScripts() {
            assertEquals("这是中文", SpeechTextNormalizer.normalizeForSpeech("这是中文"));
            assertEquals("Это русский", SpeechTextNormalizer.normalizeForSpeech("Это русский"));
        }
    }

    @Nested
    @DisplayName("end to end")
    class EndToEnd {

        private static final String REALISTIC_AGENT_REPLY = """
            ## Here's what I found 🎉

            You have **3 open orders**. Details:

            | Order | Status |
            |-------|--------|
            | A-101 | shipped |
            | A-102 | pending |

            Next steps:
            - Check the `order_id` on each
            - Read the [full guide](https://example.com/docs/orders)
            - Or email support@example.com

            ```sql
            SELECT * FROM orders;
            ```

            Let me know if that helps!
            """;

        @Test
        @DisplayName("a realistic markdown-heavy agent reply contains no pronounceable markup")
        void realisticReply() {
            String out = SpeechTextNormalizer.normalizeForSpeech(REALISTIC_AGENT_REPLY);
            assertNoSpokenMarkup(out);
            assertFalse(out.contains("#"), out);
            assertFalse(out.contains("https://"), out);
            assertFalse(out.contains("SELECT"), out);
            assertTrue(out.contains("3 open orders"), out);
            assertTrue(out.contains("order id"), out);
            // The URL was inside a markdown link, so it is dropped outright -
            // only a BARE url becomes the "a link" phrase (see the Links tests).
            assertTrue(out.contains("full guide"), out);
            assertTrue(out.contains(SpeechTextNormalizer.EMAIL_PHRASE), out);
            assertTrue(out.contains("A-101, shipped"), out);
        }

        @Test
        @DisplayName("normalizing already-normalized text changes nothing (safe to apply twice)")
        void idempotent() {
            // The TTS clients normalize their own input AND core/voice computes
            // a speechText for the browser fallback - the same string can go
            // through twice, and must not degrade on the second pass.
            for (String input : new String[] {
                REALISTIC_AGENT_REPLY,
                "## Title\n\n- **a**\n- b",
                "1. Do this\n2. Do that",
                "| a | b |\n|---|---|\n| 1 | 2 |",
                "नमस्ते! आपका स्वागत है।",
            }) {
                String once = SpeechTextNormalizer.normalizeForSpeech(input);
                assertEquals(once, SpeechTextNormalizer.normalizeForSpeech(once),
                    "second normalization pass changed the text for input: " + input);
            }
        }
    }
}
